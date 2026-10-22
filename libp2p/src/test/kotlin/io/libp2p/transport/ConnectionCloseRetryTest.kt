package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.concurrent.ImmediateEventExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConnectionCloseRetryTest {
    // Netty 4.2 exposes explicit handler execution only through this deprecated overload.
    @Suppress("DEPRECATION")
    @Test
    fun explicitRetryClosesRealConnectionAfterFailureAndKeepsOrdinaryResult() {
        val server = retryCloseHost()
        val client = retryCloseHost()
        var parent: ConnectionOverNetty? = null
        val requests = AtomicInteger()
        val failure = IOException("Cannot close test connection: physical close attempt 1 failed.")
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val connection: Connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS)
            parent = connection as ConnectionOverNetty
            connection.nettyChannel.pipeline().addLast(
                ImmediateEventExecutor.INSTANCE,
                "fail-first-close",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        if (requests.incrementAndGet() == 1) promise.setFailure(failure) else ctx.close(promise)
                    }
                }
            )
            val original = connection.close()
            assertSame(failure, retryCloseFailure(original))
            assertTrue(connection.nettyChannel.isOpen, "The injected failed close must leave the real socket open.")
            assertSame(failure, retryCloseFailure(connection.close()))
            assertEquals(1, requests.get(), "Ordinary close must keep its original result without another physical attempt.")

            connection.retryClose().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            assertFalse(connection.nettyChannel.isOpen, "An explicit retry after failure must close the real socket.")
            assertEquals(2, requests.get(), "The explicit retry must submit exactly one new physical close.")
            assertSame(failure, retryCloseFailure(original))
            assertSame(failure, retryCloseFailure(connection.close()))
            connection.retryClose().get(5, TimeUnit.SECONDS)
            assertEquals(2, requests.get(), "A successful retry is terminal; later retries must not submit another close.")
        } finally {
            parent?.let {
                if (it.nettyChannel.pipeline().context("fail-first-close") != null) {
                    it.nettyChannel.pipeline().remove("fail-first-close")
                }
                it.nettyChannel.close().get(5, TimeUnit.SECONDS)
                it.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
            }
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }
}

private fun retryCloseHost() = host {
    transports { add(::TcpTransport) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    network { listen("/ip4/127.0.0.1/tcp/0") }
}

private fun retryCloseFailure(future: CompletableFuture<Unit>): Throwable {
    var failure: Throwable = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
    while (failure.cause != null) failure = failure.cause!!
    assertEquals("Cannot close test connection: physical close attempt 1 failed.", failure.message)
    return failure
}

// Baseline-only adapter: the existing Connection API has only close(). Remove when retryClose is added.
private fun Connection.retryClose() = close()
