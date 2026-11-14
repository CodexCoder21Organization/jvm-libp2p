package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.channel.SingleThreadIoEventLoop
import io.netty.util.concurrent.ImmediateEventExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93oQueuedCloseCompletionTest {
    @Test
    fun queuedCloseCompletesOnceAcrossLoopTermination() {
        val server = host { network { listen("/ip4/127.0.0.1/tcp/0") } }
        val client = host { }
        val accepted = CompletableFuture<Connection>()
        val held = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
        val hookEntered = CompletableFuture<Unit>()
        val allowTermination = CountDownLatch(1)
        val delivered = AtomicInteger()
        val events = CopyOnWriteArrayList<String>()
        try {
            server.addConnectionHandler { accepted.complete(it) }
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(10, TimeUnit.SECONDS)
            val listener = (accepted.get(10, TimeUnit.SECONDS) as ConnectionOverNetty).nettyChannel.parent()
            val loop = listener.eventLoop() as SingleThreadIoEventLoop
            loop.submit {
                listener.closeFuture().addListener { events += "physical listener" }
                listener.pipeline().addFirst(
                    "held-close",
                    object : ChannelOutboundHandlerAdapter() {
                        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                            check(held.complete(ctx to promise))
                        }
                    }
                )
            }.get(10, TimeUnit.SECONDS)
            val close = server.network.transports.single().unlisten(Multiaddr("/ip4/127.0.0.1/tcp/0"))
            val observed = close.thenRun {
                delivered.incrementAndGet()
                events += "public result"
            }
            val request = held.get(10, TimeUnit.SECONDS)
            loop.submit {
                loop.addShutdownHook {
                    val physical = DefaultChannelPromise(listener, ImmediateEventExecutor.INSTANCE)
                    request.first.close(physical)
                    check(physical.isSuccess)
                    hookEntered.complete(Unit)
                    check(allowTermination.await(10, TimeUnit.SECONDS))
                }
            }.get(10, TimeUnit.SECONDS)
            val terminated = loop.shutdownGracefully(0, 5, TimeUnit.SECONDS)
            hookEntered.get(10, TimeUnit.SECONDS)
            assertTrue(loop.isShuttingDown)
            assertFalse(loop.isShutdown)
            assertTrue(request.second.trySuccess())
            assertFalse(close.isDone, "Accepted delivery must remain on the blocked loop")
            allowTermination.countDown()
            terminated.get(10, TimeUnit.SECONDS)
            observed.get(10, TimeUnit.SECONDS)
            assertEquals(1, delivered.get())
            assertEquals(listOf("physical listener", "public result"), events)
        } finally {
            allowTermination.countDown()
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }
}
