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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NetworkInboundReservationTest {
    @Test
    fun `inbound reuse survives the remote pending outbound result`() {
        lateinit var at: R77CallbackTransport
        lateinit var bt: R77CallbackTransport
        val a = r77Host { R77CallbackTransport(it).also { at = it } }
        val b = r77Host { R77CallbackTransport(it).also { bt = it } }
        val aInbound = CompletableFuture<Connection>()
        val bInbound = CompletableFuture<Connection>()
        a.addConnectionHandler(ConnectionHandler.create { if (!it.isInitiator) aInbound.complete(it) })
        b.addConnectionHandler(ConnectionHandler.create { if (!it.isInitiator) bInbound.complete(it) })
        try {
            a.start().get(10, TimeUnit.SECONDS)
            b.start().get(10, TimeUnit.SECONDS)
            // Both pending outbound operations exist before either physical dial starts.
            val aDial = a.network.connect(b.peerId, b.listenAddresses().single())
            val bDial = b.network.connect(a.peerId, a.listenAddresses().single())
            at.start.complete(Unit)
            bt.start.complete(Unit)
            at.upgraded.get(10, TimeUnit.SECONDS)
            bt.upgraded.get(10, TimeUnit.SECONDS)
            aInbound.get(10, TimeUnit.SECONDS)
            bInbound.get(10, TimeUnit.SECONDS)
            // Only inbound parents have reached each network's table at this point.
            val aSelected = a.network.connect(b.peerId).get(10, TimeUnit.SECONDS)
            val bSelected = b.network.connect(a.peerId).get(10, TimeUnit.SECONDS)
            assertThat(aSelected).isSameAs(aInbound.get())
            assertThat(bSelected).isSameAs(bInbound.get())
            val aPing = a.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), aSelected)
                .controller.get(10, TimeUnit.SECONDS)
            val bPing = b.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), bSelected)
                .controller.get(10, TimeUnit.SECONDS)
            aPing.ping().get(10, TimeUnit.SECONDS)
            bPing.ping().get(10, TimeUnit.SECONDS)
            // A's duplicate decision can now close B's already returned inbound parent.
            at.forwardCallback.complete(Unit)
            aDial.get(10, TimeUnit.SECONDS)
            bt.forwardCallback.complete(Unit)
            bDial.get(10, TimeUnit.SECONDS)
            assertThat(bSelected.closeFuture().isDone)
                .describedAs("Remote duplicate settlement must keep B's caller-held inbound parent open")
                .isFalse()
            aPing.ping().get(10, TimeUnit.SECONDS)
            bPing.ping().get(10, TimeUnit.SECONDS)
        } finally {
            at.start.complete(Unit)
            bt.start.complete(Unit)
            at.forwardCallback.complete(Unit)
            bt.forwardCallback.complete(Unit)
            try {
                a.stop().get(10, TimeUnit.SECONDS)
            } finally {
                b.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}

private fun r77Host(factory: (ConnectionUpgrader) -> TcpTransport): Host = host {
    network { listen("/ip4/127.0.0.1/tcp/0") }
    transports { add(factory) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    protocols { add(Ping()) }
}

// Real transport; only outbound hook forwarding and result completion are held.
private class R77CallbackTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val start = CompletableFuture<Unit>()
    val upgraded = CompletableFuture<Connection>()
    val forwardCallback = CompletableFuture<Unit>()
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> =
        start.thenCompose {
            super.dial(addr, ConnectionHandler.create { upgraded.complete(it) }, preHandler)
        }.thenCompose { connection ->
            forwardCallback.thenApply {
                connHandler.handleConnection(connection)
                connection
            }
        }
}
