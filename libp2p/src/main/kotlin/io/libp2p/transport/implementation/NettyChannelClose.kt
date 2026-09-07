package io.libp2p.transport.implementation

import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.util.concurrent.CompletableFuture

private val closeOperationKey = AttributeKey.valueOf<CompletableFuture<Unit>>("libp2p.channel.closeOperation")

/** Claims close before invoking handlers, which may re-enter through another owner. */
internal fun closeNettyChannelOnce(channel: Channel): CompletableFuture<Unit> {
    val candidate = CompletableFuture<Unit>()
    val operation = channel.attr(closeOperationKey).setIfAbsent(candidate) ?: candidate
    if (operation === candidate) {
        try {
            if (channel.closeFuture().isDone) {
                operation.complete(Unit)
            } else {
                channel.close().toVoidCompletableFuture().whenComplete { _, failure ->
                    if (failure == null) operation.complete(Unit) else operation.completeExceptionally(failure)
                }
            }
        } catch (failure: Throwable) {
            operation.completeExceptionally(failure)
        }
    }
    // Callers own only their observation; cancellation or completion cannot alter the owner.
    return operation.thenApply { Unit }
}
