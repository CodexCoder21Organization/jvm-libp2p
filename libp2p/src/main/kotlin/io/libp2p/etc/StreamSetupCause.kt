package io.libp2p.etc

import io.netty.util.AttributeKey

/** Recorded before completing setup futures so close callbacks retain the same result. */
internal val STREAM_SETUP_CAUSE: AttributeKey<Throwable> = AttributeKey.valueOf("libp2p.streamSetupCause")

internal val STREAM_SETUP_RESULT =
    AttributeKey.valueOf<java.util.concurrent.CompletableFuture<*>>("libp2p.streamSetupResult")

/** Keeps a recorded setup cause when completion re-enters from another setup callback. */
internal class StreamSetupFuture<T> : java.util.concurrent.CompletableFuture<T>() {
    @Volatile
    var channel: io.netty.channel.Channel? = null

    override fun complete(value: T): Boolean {
        val cause = channel?.attr(STREAM_SETUP_CAUSE)?.get()
        return if (cause == null) super.complete(value) else super.completeExceptionally(cause)
    }

    override fun completeExceptionally(ex: Throwable): Boolean =
        super.completeExceptionally(channel?.attr(STREAM_SETUP_CAUSE)?.get() ?: ex)

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cause = channel?.attr(STREAM_SETUP_CAUSE)?.get()
        return if (cause == null) super.cancel(mayInterruptIfRunning) else super.completeExceptionally(cause)
    }
}
