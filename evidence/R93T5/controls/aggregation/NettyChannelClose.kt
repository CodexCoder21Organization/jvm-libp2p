package io.libp2p.transport.implementation

import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.channel.Channel
import io.netty.channel.DefaultChannelPromise
import io.netty.util.AttributeKey
import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private class ChannelCloseState {
    val original = CompletableFuture<Unit>()
    val latest = AtomicReference(original)
}

private val closeOperationKey = AttributeKey.valueOf<ChannelCloseState>("libp2p.channel.closeOperation")

private val closeNotificationKey = AttributeKey.valueOf<AtomicInteger>("libp2p.channel.closeNotificationDepth")

/** Track public close callbacks rather than unrelated nested Netty promise notifications. */
internal fun observeConnectionClose(
    channel: Channel,
    source: CompletableFuture<Unit>
): CompletableFuture<Unit> {
    val candidate = AtomicInteger()
    val depth = channel.attr(closeNotificationKey).setIfAbsent(candidate) ?: candidate
    val result = CompletableFuture<Unit>()
    source.whenComplete { _, failure ->
        depth.incrementAndGet()
        try {
            if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
        } finally {
            depth.decrementAndGet()
        }
    }
    return result
}

/** Ordinary owners always observe the original close, even after an explicit retry. */
internal fun closeNettyChannelOnce(channel: Channel): CompletableFuture<Unit> = closeOperation(channel, retry = false).thenApply { Unit }

/** Only a settled failure permits a new physical attempt; pending callers share its owner. */
internal fun retryNettyChannelClose(channel: Channel): CompletableFuture<Unit> = closeOperation(channel, retry = true).thenApply { Unit }

private fun closeOperation(
    channel: Channel,
    retry: Boolean
): CompletableFuture<Unit> {
    val candidateState = ChannelCloseState()
    val state = channel.attr(closeOperationKey).setIfAbsent(candidateState) ?: candidateState
    // Publish ownership before handlers or future callbacks can re-enter.
    if (state === candidateState) {
        requestPhysicalClose(channel, state.original)
        return state.original
    }
    if (!retry) return state.original
    while (true) {
        val latest = state.latest.get()
        if (!latest.isCompletedExceptionally) return latest
        val candidate = CompletableFuture<Unit>()
        if (state.latest.compareAndSet(latest, candidate)) {
            requestPhysicalClose(channel, candidate)
            return candidate
        }
    }
}

private fun requestPhysicalClose(
    channel: Channel,
    operation: CompletableFuture<Unit>
) {
    try {
        if (channel.closeFuture().isDone) {
            if ((channel.attr(closeNotificationKey).get()?.get() ?: 0) > 0) {
                // Callback handoffs to another thread still join the active physical-close notification.
                channel.closeFuture().addListener { operation.complete(Unit) }
            } else {
                // The terminal result must not depend on another accepted notification task.
                operation.complete(Unit)
            }
        } else {
            val loop = channel.eventLoop()
            val completionExecutor = object : EventExecutor by loop {
                override fun execute(command: Runnable) {
                    val claimed = AtomicBoolean()
                    val termination = loop.terminationFuture()
                    val onTermination = GenericFutureListener<Future<Any?>> {
                        if (claimed.compareAndSet(false, true)) command.run()
                    }
                    val deliver = Runnable {
                        if (claimed.compareAndSet(false, true)) {
                            termination.removeListener(onTermination)
                            command.run()
                        }
                    }
                    termination.addListener(onTermination)
                    try {
                        loop.execute(deliver)
                    } catch (_: RejectedExecutionException) {
                        // A rejected notification still owns delivery of the physical close result.
                        deliver.run()
                    }
                    // Submission may return normally after the loop discarded its final queue.
                    // The listener covers termination after this read; both paths share the claim.
                    if (loop.isTerminated) deliver.run()
                }
            }
            // Preserve the loop's listener order, including its notification-depth boundary.
            val promise = DefaultChannelPromise(channel, completionExecutor)
            channel.close(promise).toVoidCompletableFuture().whenComplete { _, failure ->
                if (failure == null) operation.complete(Unit) else operation.completeExceptionally(failure)
            }
        }
    } catch (failure: Throwable) {
        operation.completeExceptionally(failure)
    }
}

/** Every operation has settled before its shutdown result is assembled. */
internal fun transportCloseResult(operations: List<CompletableFuture<*>>) {
    var primary: Throwable? = null
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    operations.forEach { operation ->
        val failure = operation.handle { _, failure -> failure }.join()
        val original = if (failure is CompletionException) failure.cause ?: failure else failure
        if (original != null && seen.add(original)) {
            val first = primary
            if (first == null) {
                primary = original
            } else if (first !== original && first.suppressed.none { it === original }) {
                first.addSuppressed(original)
                if (first.suppressed.none { it === original }) {
                    primary =
                        IllegalStateException("Transport shutdown reported multiple failures", first).apply {
                            addSuppressed(original)
                        }
                }
            }
        }
    }
    primary?.let { throw CompletionException(it) }
}
