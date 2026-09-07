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
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
}

private fun closeOwnershipHost() = host {
    transports { add(::TcpTransport) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    network { listen("/ip4/127.0.0.1/tcp/0") }
}
