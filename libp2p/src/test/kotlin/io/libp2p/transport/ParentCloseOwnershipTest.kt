package io.libp2p.transport

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
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ParentCloseOwnershipTest {
    @Test
    fun networkAndConnectionShutdownShareOnePendingPhysicalClose() {
        val server = closeOwnershipHost()
        val client = closeOwnershipHost()
        val heldCloses = CopyOnWriteArrayList<Pair<ChannelHandlerContext, ChannelPromise>>()
        val hold = AtomicBoolean(true)
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS) as ConnectionOverNetty
            connection.nettyChannel.pipeline().addLast(
                ImmediateEventExecutor.INSTANCE,
                "hold-parent-close",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        heldCloses.add(ctx to promise)
                        if (!hold.get()) ctx.close(promise)
                    }
                }
            )
            val firstShutdown = client.network.close()
            val explicitClose = connection.close()
            val repeatedShutdown = client.network.close()
            assertFalse(connection.closeFuture().isDone, "The first physical close must remain held until released.")
            assertEquals(
                1, heldCloses.size,
                "One parent received ${heldCloses.size} physical close requests while the first was pending; " +
                    "transport and connection shutdown must share the owner's in-flight close."
            )
            // A caller can complete its CompletableFuture, but cannot complete the
            // close owned by the transport or release another shutdown caller.
            explicitClose.complete(Unit)
            assertFalse(firstShutdown.isDone, "Completing one caller's future must not finish network shutdown.")
            assertFalse(repeatedShutdown.isDone, "A later shutdown must still await the physical close.")
            hold.set(false)
            heldCloses.toList().forEach { (ctx, promise) -> ctx.close(promise) }
            CompletableFuture.allOf(firstShutdown, explicitClose, repeatedShutdown).get(5, TimeUnit.SECONDS)
            connection.close().get(5, TimeUnit.SECONDS)
            client.network.close().get(5, TimeUnit.SECONDS)
            assertEquals(1, heldCloses.size, "A completed parent close must stay terminal on repeated shutdown.")
        } finally {
            hold.set(false)
            heldCloses.toList().filter { !it.second.isDone }.forEach { (ctx, promise) -> ctx.close(promise) }
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun failedCloseIsReturnedToEveryOwnerWithoutAnotherRequest() {
        val server = closeOwnershipHost()
        val client = closeOwnershipHost()
        var connection: ConnectionOverNetty? = null
        val requests = AtomicInteger()
        val failure = IOException("Cannot close the test parent: the injected transport close operation failed.")
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val parent = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS) as ConnectionOverNetty
            connection = parent
            parent.nettyChannel.pipeline().addLast(
                ImmediateEventExecutor.INSTANCE,
                "fail-parent-close",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        requests.incrementAndGet()
                        promise.setFailure(failure)
                    }
                }
            )
            val first = assertThrows(ExecutionException::class.java) { parent.close().get(5, TimeUnit.SECONDS) }
            val shutdown = assertThrows(ExecutionException::class.java) { client.network.close().get(5, TimeUnit.SECONDS) }
            for (reported in listOf(first, shutdown)) {
                var cause: Throwable = reported
                while (cause.cause != null) cause = cause.cause!!
                assertSame(failure, cause, "Every close owner must observe the original transport failure.")
                assertEquals("Cannot close the test parent: the injected transport close operation failed.", cause.message)
            }
            assertEquals(1, requests.get(), "A failed close is terminal; another owner must observe its result without issuing another physical close.")
        } finally {
            // Release the fixture's real socket directly after its deliberate API failure.
            // This is test cleanup, outside the connection/transport close contract under test.
            connection?.let { parent ->
                parent.nettyChannel.pipeline().remove("fail-parent-close")
                parent.nettyChannel.close().get(5, TimeUnit.SECONDS)
                parent.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
            }
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }

}

private fun closeOwnershipHost() = host {
    transports { add(::TcpTransport) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    network { listen("/ip4/127.0.0.1/tcp/0") }
}
