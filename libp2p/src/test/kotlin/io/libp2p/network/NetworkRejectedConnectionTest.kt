package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkRejectedConnectionTest {
    @Test
    fun `reuse excludes a duplicate whose physical close is pending`() {
        val server = r77ClosingHost(true)
        lateinit var transport: CallbackResultTransport
        val client = r77ClosingHost(false) { CallbackResultTransport(it).also { transport = it } }
        val closeGate = HeldPhysicalClose()
        val visits = AtomicInteger()
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val addresses = server.listenAddresses().toTypedArray()
            val winner = client.network.connect(
                server.peerId,
                ChannelVisitor {
                    if (visits.incrementAndGet() == 1) it.pushHandler(closeGate)
                },
                *addresses
            ).get(10, TimeUnit.SECONDS)
            transport.releaseFirst.complete(Unit)
            closeGate.requested.get(10, TimeUnit.SECONDS)
            // The first parent is already rejected, but its channel close is not complete.
            winner.close().get(10, TimeUnit.SECONDS)
            val selected = client.network.connect(server.peerId, *addresses).get(10, TimeUnit.SECONDS)
            assertThat(selected)
                .describedAs("Reuse must exclude a duplicate already scheduled for physical close")
                .isNotSameAs(transport.firstUpgraded.get(10, TimeUnit.SECONDS))
            val ping = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), selected)
                .controller.get(10, TimeUnit.SECONDS)
            ping.ping().get(10, TimeUnit.SECONDS)
            closeGate.release.complete(Unit)
            transport.firstUpgraded.get().closeFuture().get(10, TimeUnit.SECONDS)
            assertThat(selected.closeFuture().isDone)
                .describedAs("Reuse must exclude a duplicate already scheduled for physical close")
                .isFalse()
        } finally {
            transport.releaseFirst.complete(Unit)
            closeGate.release.complete(Unit)
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}

private fun r77ClosingHost(listen: Boolean, factory: ((ConnectionUpgrader) -> TcpTransport)? = null): Host = host {
    if (listen) {
        network {
            listen("/ip4/127.0.0.1/tcp/0")
            listen("/ip4/127.0.0.2/tcp/0")
        }
    }
    factory?.let { transports { add(it) } }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    protocols { add(Ping()) }
}

private class HeldPhysicalClose : ChannelOutboundHandlerAdapter() {
    val requested = CompletableFuture<Unit>()
    val release = CompletableFuture<Unit>()
    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        requested.complete(Unit)
        release.thenRun { ctx.close(promise) }
    }
}

private class CallbackResultTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val firstUpgraded = CompletableFuture<Connection>()
    val releaseFirst = CompletableFuture<Unit>()
    private val start = CompletableFuture<Unit>()
    private val count = AtomicInteger()
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> =
        when (count.incrementAndGet()) {
            1 -> start.thenCompose { super.dial(addr, connHandler, preHandler) }.thenCompose { connection ->
                firstUpgraded.complete(connection)
                releaseFirst.thenApply { connection }
            }
            2 -> {
                start.complete(Unit)
                firstUpgraded.thenCompose { super.dial(addr, connHandler, preHandler) }
            }
            else -> super.dial(addr, connHandler, preHandler)
        }
}
