package io.libp2p.etc

import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.function.Supplier

/** Recorded before completing setup futures so close callbacks retain the same result. */
internal val STREAM_SETUP_CAUSE: AttributeKey<Throwable> = AttributeKey.valueOf("libp2p.streamSetupCause")

internal val STREAM_SETUP_RESULT =
    AttributeKey.valueOf<CompletableFuture<*>>("libp2p.streamSetupResult")

/** Keeps a recorded setup cause when completion re-enters from another setup callback. */
internal class StreamSetupFuture<T> : CompletableFuture<T>() {
    @Volatile
    var channel: Channel? = null

    override fun complete(value: T): Boolean {
        val cause = channel?.attr(STREAM_SETUP_CAUSE)?.get()
        return if (cause == null) super.complete(value) else super.completeExceptionally(cause)
    }

    override fun completeExceptionally(ex: Throwable): Boolean =
        super.completeExceptionally(channel?.attr(STREAM_SETUP_CAUSE)?.get() ?: ex)

    override fun completeAsync(supplier: Supplier<out T>): CompletableFuture<T> =
        completeAsync(supplier, defaultExecutor())

    override fun completeAsync(supplier: Supplier<out T>, executor: Executor): CompletableFuture<T> =
        super.completeAsync({
            channel?.attr(STREAM_SETUP_CAUSE)?.get()?.let { throw CompletionException(it) }
            try {
                val value = supplier.get()
                channel?.attr(STREAM_SETUP_CAUSE)?.get()?.let { throw CompletionException(it) }
                value
            } catch (cause: Throwable) {
                channel?.attr(STREAM_SETUP_CAUSE)?.get()?.let { throw CompletionException(it) }
                throw cause
            }
        }, executor)

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cause = channel?.attr(STREAM_SETUP_CAUSE)?.get()
        if (cause == null) return super.cancel(mayInterruptIfRunning)
        super.completeExceptionally(cause)
        return isCancelled
    }
}
