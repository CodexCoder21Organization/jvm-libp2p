package io.libp2p.transport.implementation

import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.util.concurrent.CompletableFuture
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
internal fun closeNettyChannelOnce(channel: Channel): CompletableFuture<Unit> =
    closeOperation(channel, retry = false).thenApply { Unit }

/** Only a settled failure permits a new physical attempt; pending callers share its owner. */
internal fun retryNettyChannelClose(channel: Channel): CompletableFuture<Unit> =
    closeOperation(channel, retry = true).thenApply { Unit }

private fun closeOperation(channel: Channel, retry: Boolean): CompletableFuture<Unit> {
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

private fun requestPhysicalClose(channel: Channel, operation: CompletableFuture<Unit>) {
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
            channel.close().toVoidCompletableFuture().whenComplete { _, failure ->
                if (failure == null) operation.complete(Unit) else operation.completeExceptionally(failure)
            }
        }
    } catch (failure: Throwable) {
        operation.completeExceptionally(failure)
    }
}
