package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class NetworkReservationDecoratedDuplicateTest {
    @Test
    fun `decorated surplus dials retire while the selected parent remains usable`() {
        lateinit var transport: DecoratedDuplicateTransport
        val a = host {
            transports { add { DecoratedDuplicateTransport(it).also { transport = it } } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        val b = host {
            network { (1..3).forEach { listen("/ip4/127.0.0.$it/tcp/0") } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        try {
            a.start().get(10, SECONDS)
            b.start().get(10, SECONDS)
            val selected = a.network.connect(b.peerId, *b.listenAddresses().toTypedArray()).get(10, SECONDS)
            assertThat(transport.dials).hasSize(3)
            val dialled = transport.dials.map { it.get(10, SECONDS) }
            val surplus = dialled.filter { it !== selected }
            assertThat(surplus).hasSize(2)
            val closed = CountDownLatch(2)
            surplus.forEach { it.closeFuture().thenRun { closed.countDown() } }
            assertThat(closed.await(10, SECONDS))
                .describedAs("Coordinated surplus sessions must retire through Connection decorators").isTrue()
            assertThat(a.network.connect(b.peerId).get(10, SECONDS)).isSameAs(selected)
            assertThat(a.network.connections.filter { !it.closeFuture().isDone }).containsExactly(selected)
            assertThat(a.network.reserveConnection(selected).get(10, SECONDS)).isTrue()
            val echo = a.newStream<EchoController>(listOf("/test/echo"), selected).controller.get(10, SECONDS)
            assertThat(echo.echo("decorated selection").get(10, SECONDS)).isEqualTo("decorated selection")
        } finally {
            transport.start.complete(Unit)
            try {
                a.stop().get(10, SECONDS)
            } finally {
                b.stop().get(10, SECONDS)
            }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private class DecoratedDuplicateTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val start = CompletableFuture<Unit>()
    val dials = CopyOnWriteArrayList<CompletableFuture<Connection>>()
    private val count = AtomicInteger()

    override fun dial(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Connection> {
        val published = CompletableFuture<Connection>()
        val result = start.thenCompose {
            super.dial(
                addr,
                ConnectionHandler.create { parent ->
                    val decorated = object : Connection by parent {}
                    published.complete(decorated)
                    connHandler.handleConnection(decorated)
                },
                preHandler
            ).thenCompose { published }
        }
        dials.add(result)
        if (count.incrementAndGet() == 3) start.complete(Unit)
        return result
    }
}
