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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class NetworkThreeParentTest {
    @Test
    fun `two outbound and one inbound callback parents survive all duplicate decisions`() {
        lateinit var at: ThreeWayTransport
        lateinit var bt: ThreeWayTransport
        val a = threeWayHost(false) { ThreeWayTransport(it).also { at = it } }
        val b = threeWayHost(true) { ThreeWayTransport(it).also { bt = it } }
        val aParents = CopyOnWriteArrayList<Connection>()
        val bParents = CopyOnWriteArrayList<Connection>()
        val aPings = CopyOnWriteArrayList<CompletableFuture<PingController>>()
        val bPings = CopyOnWriteArrayList<CompletableFuture<PingController>>()
        val aReady = CompletableFuture<Unit>()
        val bReady = CompletableFuture<Unit>()
        fun publish(host: Host, parents: MutableList<Connection>, pings: MutableList<CompletableFuture<PingController>>, ready: CompletableFuture<Unit>, connection: Connection) {
            parents += connection
            pings += host.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), connection).controller
            if (parents.size == 3) ready.complete(Unit)
        }
        a.addConnectionHandler(ConnectionHandler.create { publish(a, aParents, aPings, aReady, it) })
        b.addConnectionHandler(ConnectionHandler.create { publish(b, bParents, bPings, bReady, it) })
        try {
            a.start().get(10, TimeUnit.SECONDS)
            b.start().get(10, TimeUnit.SECONDS)
            val aDial = a.network.connect(b.peerId, *b.listenAddresses().toTypedArray())
            val bDial = b.network.connect(a.peerId, a.listenAddresses().single())
            // All pending dials are installed before real sockets are opened.
            at.start.complete(Unit)
            bt.start.complete(Unit)
            aReady.get(10, TimeUnit.SECONDS)
            bReady.get(10, TimeUnit.SECONDS)
            val pings = (aPings + bPings).map { it.get(10, TimeUnit.SECONDS) }
            pings.forEach { it.ping().get(10, TimeUnit.SECONDS) }
            val selectedA = a.network.connect(b.peerId).get(10, TimeUnit.SECONDS)
            val selectedB = b.network.connect(a.peerId).get(10, TimeUnit.SECONDS)
            at.results.complete(Unit)
            bt.results.complete(Unit)
            aDial.get(10, TimeUnit.SECONDS)
            bDial.get(10, TimeUnit.SECONDS)
            // Dial completion runs duplicate selection synchronously for every address subscriber.
            at.finished().get(10, TimeUnit.SECONDS)
            bt.finished().get(10, TimeUnit.SECONDS)
            pings.forEach { it.ping().get(10, TimeUnit.SECONDS) }
            (aParents + bParents).forEach { assertThat(it.closeFuture().isDone).isFalse() }
            assertThat(a.network.connect(b.peerId).get(10, TimeUnit.SECONDS)).isSameAs(selectedA)
            assertThat(b.network.connect(a.peerId).get(10, TimeUnit.SECONDS)).isSameAs(selectedB)
        } finally {
            at.start.complete(Unit)
            bt.start.complete(Unit)
            at.results.complete(Unit)
            bt.results.complete(Unit)
            try {
                a.stop().get(10, TimeUnit.SECONDS)
            } finally {
                b.stop().get(10, TimeUnit.SECONDS)
            }
            (aParents + bParents).forEach { it.closeFuture().get(10, TimeUnit.SECONDS) }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private fun threeWayHost(twoListeners: Boolean, factory: (ConnectionUpgrader) -> TcpTransport) = host {
    network {
        listen("/ip4/127.0.0.1/tcp/0")
        if (twoListeners) listen("/ip4/127.0.0.2/tcp/0")
    }
    transports { add(factory) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    protocols { add(Ping()) }
}

private class ThreeWayTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val start = CompletableFuture<Unit>()
    val results = CompletableFuture<Unit>()
    private val delivered = CopyOnWriteArrayList<CompletableFuture<Connection>>()
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> {
        val result = start.thenCompose { super.dial(addr, connHandler, preHandler) }
            .thenCompose { connection -> results.thenApply { connection } }
        delivered += result
        return result
    }
    fun finished(): CompletableFuture<Void> = CompletableFuture.allOf(*delivered.toTypedArray())
}
