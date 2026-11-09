package io.libp2p.transport.quic

import io.netty.channel.DefaultSelectStrategyFactory
import io.netty.channel.IoHandler
import io.netty.channel.IoHandlerFactory
import io.netty.channel.SelectStrategy
import io.netty.channel.SelectStrategyFactory
import io.netty.channel.nio.NioIoHandler
import io.netty.util.IntSupplier
import io.netty.util.concurrent.ThreadAwareExecutor
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.CompletableFuture

/** A closed JDK datagram channel releases its descriptor when its cancelled key is removed. */
internal class DatagramPortRelease(private val wakeup: () -> Unit) : SelectStrategy {
    private val ordinary = DefaultSelectStrategyFactory.INSTANCE.newSelectStrategy()
    private val waiting = mutableListOf<CompletableFuture<Unit>>()
    private var destroyed = false

    fun awaitRelease(): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        val enqueue = synchronized(this) {
            if (destroyed) {
                false
            } else {
                waiting.add(result)
                true
            }
        }
        if (enqueue) wakeup() else result.complete(Unit)
        return result
    }

    override fun calculateStrategy(selectSupplier: IntSupplier, hasTasks: Boolean): Int {
        val batch = synchronized(this) {
            waiting.toList().also { waiting.clear() }
        }
        if (batch.isEmpty()) return ordinary.calculateStrategy(selectSupplier, hasTasks)

        val selected = try {
            selectSupplier.get()
        } catch (failure: Throwable) {
            synchronized(this) { waiting.addAll(0, batch) }
            throw failure
        }
        // Re-entry adds to the next batch, after this selector pass has already finished.
        batch.forEach { it.complete(Unit) }
        return selected
    }

    fun completeAfterDestroy() {
        val batch = synchronized(this) {
            destroyed = true
            waiting.toList().also { waiting.clear() }
        }
        batch.forEach { it.complete(Unit) }
    }
}

internal fun datagramPortReleaseIoFactory(
    releases: MutableMap<Any, DatagramPortRelease>
): IoHandlerFactory = object : IoHandlerFactory {
    override fun newHandler(executor: ThreadAwareExecutor): IoHandler {
        lateinit var handler: IoHandler
        val release = DatagramPortRelease { handler.wakeup() }
        handler = NioIoHandler.newFactory(
            SelectorProvider.provider(),
            SelectStrategyFactory { release }
        ).newHandler(executor)
        releases[executor] = release
        return object : IoHandler by handler {
            override fun destroy() {
                handler.destroy()
                release.completeAfterDestroy()
            }
        }
    }

    override fun isChangingThreadSupported(): Boolean = true
}
