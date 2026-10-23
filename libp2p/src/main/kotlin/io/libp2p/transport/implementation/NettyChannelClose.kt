package io.libp2p.transport.implementation

import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

private class ChannelCloseState {
    val original = CompletableFuture<Unit>()
    val latest = AtomicReference(original)
}

private val closeOperationKey = AttributeKey.valueOf<ChannelCloseState>("libp2p.channel.closeOperation")

/** Ordinary owners always observe the original close, even after an explicit retry. */
internal fun closeNettyChannelOnce(channel: Channel): CompletableFuture<Unit> =
    closeOperation(channel, retry = false).thenApply { Unit }

/** Only a settled failure permits a new physical attempt; pending callers share its owner. */
internal fun retryNettyChannelClose(channel: Channel): CompletableFuture<Unit> =
    if (channel.closeFuture().isDone) CompletableFuture.completedFuture(Unit) else closeOperation(channel, retry = true).thenApply { Unit }

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
            // An early close observer may run before other physical-close listeners finish.
            channel.closeFuture().addListener { operation.complete(Unit) }
        } else {
            channel.close().toVoidCompletableFuture().whenComplete { _, failure ->
                if (failure == null) operation.complete(Unit) else operation.completeExceptionally(failure)
            }
        }
    } catch (failure: Throwable) {
        operation.completeExceptionally(failure)
    }
}
