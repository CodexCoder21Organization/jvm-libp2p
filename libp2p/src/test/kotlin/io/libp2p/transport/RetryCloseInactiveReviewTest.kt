package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.concurrent.ImmediateEventExecutor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RetryCloseInactiveReviewTest {
    @Suppress("DEPRECATION")
    @Test
    fun pendingRetryKeepsItsResultWhenPeerClosureReentersBothCloseApis() {
        val remote = CompletableFuture<Connection>()
        val server = inactiveReviewHost { remote.complete(it) }
        val client = inactiveReviewHost()
        var parent: ConnectionOverNetty? = null
        val held = AtomicReference<ChannelPromise>()
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS) as ConnectionOverNetty
            parent = connection
            val requests = AtomicInteger()
            val initialFailure = IOException("Cannot close review connection: physical close attempt 1 failed.")
            val retryFailure = IOException("Cannot close review connection: physical close attempt 2 failed.")
            val inactive = CompletableFuture<Unit>()
            val reenteredRetry = AtomicReference<CompletableFuture<Unit>>()
            val reenteredOrdinary = AtomicReference<CompletableFuture<Unit>>()
            connection.nettyChannel.pipeline().addLast(
                ImmediateEventExecutor.INSTANCE,
                "review-close-control",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        when (requests.incrementAndGet()) {
                            1 -> promise.setFailure(initialFailure)
                            2 -> held.set(promise)
                            else -> ctx.close(promise)
                        }
                    }
                }
            )
            connection.pushHandler(
                "review-inactive",
                object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        try {
                            reenteredOrdinary.set(connection.close())
                            reenteredRetry.set(connection.retryClose())
                            inactive.complete(Unit)
                        } catch (failure: Throwable) {
                            inactive.completeExceptionally(failure)
                        } finally {
                            ctx.fireChannelInactive()
                        }
                    }
                }
            )
            connection.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
            assertSame(initialFailure, inactiveReviewFailure(connection.close()))
            val retry = connection.retryClose()
            assertEquals(2, requests.get())
            assertFalse(retry.isDone)

            // Peer EOF closes the socket independently of the held outbound close promise.
            remote.get(5, TimeUnit.SECONDS).close().get(5, TimeUnit.SECONDS)
            inactive.get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            assertFalse(connection.nettyChannel.isOpen)
            assertSame(initialFailure, inactiveReviewFailure(reenteredOrdinary.get()))
            val observations = listOf(retry, reenteredRetry.get(), connection.retryClose())
            assertTrue(observations.none { it.isDone }, "Peer closure must not replace a pending attempt's own result with success.")
            assertEquals(2, requests.get(), "Re-entry and peer closure must not issue another physical close.")
            val completions = AtomicInteger()
            val reports = observations.map { observation ->
                observation.handle { _, failure ->
                    completions.incrementAndGet()
                    failure
                }
            }
            held.get().setFailure(retryFailure)
            reports.forEach { report ->
                var failure: Throwable = report.get(5, TimeUnit.SECONDS)
                    ?: error("Pending retry reported success instead of its physical attempt's failure.")
                while (failure.cause != null) failure = failure.cause!!
                assertSame(retryFailure, failure)
                assertEquals("Cannot close review connection: physical close attempt 2 failed.", failure.message)
            }
            assertEquals(3, completions.get(), "Each retry observation must complete exactly once.")
            connection.retryClose().get(5, TimeUnit.SECONDS)
            assertEquals(2, requests.get(), "An already closed socket needs no third physical request.")
            assertSame(initialFailure, inactiveReviewFailure(connection.close()))
        } finally {
            held.get()?.tryFailure(IOException("Review cleanup released an unfinished close attempt."))
            try {
                parent?.let { connection ->
                    connection.nettyChannel.eventLoop().submit {
                        for (name in listOf("review-close-control", "review-inactive")) {
                            if (connection.nettyChannel.pipeline().context(name) != null) {
                                connection.nettyChannel.pipeline().remove(name)
                            }
                        }
                    }.get(5, TimeUnit.SECONDS)
                    connection.nettyChannel.close().get(5, TimeUnit.SECONDS)
                    connection.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
                }
            } finally {
                CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
            }
        }
    }
}

private fun inactiveReviewHost(onConnection: (Connection) -> Unit = {}) = host {
    connectionHandlers { add(ConnectionHandler.create(onConnection)) }
    transports { add(::TcpTransport) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    network { listen("/ip4/127.0.0.1/tcp/0") }
}

private fun inactiveReviewFailure(future: CompletableFuture<Unit>): Throwable {
    var failure: Throwable = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
    while (failure.cause != null) failure = failure.cause!!
    assertEquals("Cannot close review connection: physical close attempt 1 failed.", failure.message)
    return failure
}
