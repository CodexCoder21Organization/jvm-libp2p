package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkLegacyPublishedParentTest {
    @Test
    fun `a remote published parent survives when selection support is absent`() {
        val b = host {
            network {
                listen("/ip4/127.0.0.1/tcp/0")
                listen("/ip4/127.0.0.2/tcp/0")
            }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers {
                add(
                    StreamMuxerProtocol { multistream, protocols ->
                        StreamMuxerProtocol.getYamux().createMuxer(
                            multistream,
                            protocols.filterNot { it.protocolDescriptor.matchesAny(listOf("/libp2p/connection-selection/1.0.0")) }
                        )
                    }
                )
            }
            protocols {
                add(Ping())
                add(Echo())
            }
        }
        val accepted = CompletableFuture<Connection>()
        b.addConnectionHandler(ConnectionHandler.create { accepted.complete(it) })
        lateinit var transport: LegacyPublishedTransport
        val a = host {
            transports { add { upgrader -> LegacyPublishedTransport(upgrader).also { transport = it } } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols {
                add(Ping())
                add(Echo())
            }
        }
        val controlClosed = CompletableFuture<Unit>()
        a.addStreamVisitor(
            ChannelVisitor { stream ->
                // Only the library can open a child on the unused duplicate in this scenario.
                if (transport.first.isDone && stream.connection === transport.first.get()) {
                    stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                        override fun channelUnregistered(ctx: ChannelHandlerContext) {
                            ctx.fireChannelUnregistered()
                            ctx.executor().execute { controlClosed.complete(Unit) }
                        }
                    })
                }
            }
        )
        try {
            a.start().get(10, TimeUnit.SECONDS)
            b.start().get(10, TimeUnit.SECONDS)
            val selected = a.network.connect(b.peerId, *b.listenAddresses().toTypedArray()).get(10, TimeUnit.SECONDS)
            val unused = transport.first.get(10, TimeUnit.SECONDS)
            assertThat(selected).isNotSameAs(unused)
            val ping = a.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), selected)
                .controller.get(10, TimeUnit.SECONDS)
            ping.ping().get(10, TimeUnit.SECONDS)
            val remote = b.network.connect(a.peerId).get(10, TimeUnit.SECONDS)
            assertThat(remote).isSameAs(accepted.get(10, TimeUnit.SECONDS))
            assertThat(remote.localAddress()).isEqualTo(unused.remoteAddress())
            assertThat(remote.remoteAddress()).isEqualTo(unused.localAddress())
            // No application child exists on this parent before local retirement starts.
            transport.releaseFirst.complete(Unit)
            // Develop closes the surplus; the revised head finishes a failed control child instead.
            CompletableFuture.anyOf(unused.closeFuture(), controlClosed).get(10, TimeUnit.SECONDS)
            ping.ping().get(10, TimeUnit.SECONDS)
            val remotePing = b.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), remote)
                .controller.get(10, TimeUnit.SECONDS)
            remotePing.ping().get(10, TimeUnit.SECONDS)
            assertThat(remote.closeFuture().isDone).isFalse()
            assertThat(unused.closeFuture().isDone).isFalse()
            assertThat(transport.activeConnections).isEqualTo(2)
            selected.close().get(10, TimeUnit.SECONDS)
            assertThat(a.network.connect(b.peerId).get(10, TimeUnit.SECONDS)).isSameAs(unused)
            val recovered = a.newStream<EchoController>(listOf("/test/echo"), unused)
                .controller.get(10, TimeUnit.SECONDS)
            assertThat(recovered.echo("remote published parent").get(10, TimeUnit.SECONDS))
                .isEqualTo("remote published parent")
        } finally {
            transport.start.complete(Unit)
            transport.releaseFirst.complete(Unit)
            try {
                a.stop().get(10, TimeUnit.SECONDS)
            } finally {
                b.stop().get(10, TimeUnit.SECONDS)
            }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private class LegacyPublishedTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val first = CompletableFuture<Connection>()
    val start = CompletableFuture<Unit>()
    val releaseFirst = CompletableFuture<Unit>()
    private val count = AtomicInteger()
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> =
        when (count.incrementAndGet()) {
            1 -> start.thenCompose { super.dial(addr, connHandler, preHandler) }.thenCompose { connection ->
                first.complete(connection)
                releaseFirst.thenApply { connection }
            }
            2 -> {
                start.complete(Unit)
                first.thenCompose { super.dial(addr, connHandler, preHandler) }
            }
            else -> error("Expected only two genuine transport dials")
        }
}
