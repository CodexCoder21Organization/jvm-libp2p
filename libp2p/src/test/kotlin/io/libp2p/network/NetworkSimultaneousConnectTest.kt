package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkSimultaneousConnectTest {
    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun stopHosts() {
        hosts.asReversed().forEach { it.stop().get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun `simultaneous peer connects leave one shared connection open`() {
        val releaseBothDials = CompletableFuture<Unit>()
        val (first, firstTransport) = createHost(releaseBothDials)
        val (second, secondTransport) = createHost(releaseBothDials)

        val firstConnect = first.network.connect(
            second.peerId,
            second.listenAddresses().single()
        )
        val secondConnect = second.network.connect(
            first.peerId,
            first.listenAddresses().single()
        )

        assertThat(firstTransport.dialCount.get()).isEqualTo(1)
        assertThat(secondTransport.dialCount.get()).isEqualTo(1)
        assertThat(firstConnect.isDone).isFalse()
        assertThat(secondConnect.isDone).isFalse()

        releaseBothDials.complete(Unit)
        CompletableFuture.allOf(firstConnect, secondConnect).get(30, TimeUnit.SECONDS)
        assertPreferredConnectSelection(first, second)
        assertPreferredConnectSelection(second, first)
        awaitNonPreferredConnectionClose(first, second.peerId)
        awaitNonPreferredConnectionClose(second, first.peerId)

        assertThat(activeConnectionCount(first))
            .describedAs("active connections the first peer holds to the second")
            .isEqualTo(1)
        assertThat(activeConnectionCount(second))
            .describedAs("active connections the second peer holds to the first")
            .isEqualTo(1)

        first.newStream<PingController>(
            listOf(PING_PROTOCOL),
            second.peerId,
            second.listenAddresses().single()
        ).controller.thenCompose { it.ping() }.get(30, TimeUnit.SECONDS)
        second.newStream<PingController>(
            listOf(PING_PROTOCOL),
            first.peerId,
            first.listenAddresses().single()
        ).controller.thenCompose { it.ping() }.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `rejected connection stays unavailable while close completion is delayed`() {
        val firstDialGate = CompletableFuture<Unit>()
        val secondDialGate = CompletableFuture<Unit>()
        val firstCloseCompletion = CompletableFuture<Unit>()
        val secondCloseCompletion = CompletableFuture<Unit>()
        val (first, firstTransport) = createDelayedCloseHost(firstDialGate, firstCloseCompletion)
        val (second, secondTransport) = createDelayedCloseHost(secondDialGate, secondCloseCompletion)

        val firstConnect = first.network.connect(
            second.peerId,
            second.listenAddresses().single()
        )
        val secondConnect = second.network.connect(
            first.peerId,
            first.listenAddresses().single()
        )
        val lowerPeer = if (first.peerId.toBase58() < second.peerId.toBase58()) first else second
        val higherPeer = if (lowerPeer === first) second else first
        val lowerDialGate = if (lowerPeer === first) firstDialGate else secondDialGate
        val higherDialGate = if (higherPeer === first) firstDialGate else secondDialGate
        val higherConnect = if (higherPeer === first) firstConnect else secondConnect

        try {
            // The higher-ID peer's initiator connection is the direction both peers must reject.
            // Establish it first so the preferred connection has to replace a known older entry.
            higherDialGate.complete(Unit)
            higherConnect.get(30, TimeUnit.SECONDS)

            lowerDialGate.complete(Unit)
            CompletableFuture.allOf(firstConnect, secondConnect).get(30, TimeUnit.SECONDS)
            val firstRejected = firstTransport.awaitRejectedConnection()
            val secondRejected = secondTransport.awaitRejectedConnection()
            val firstSurvivor = firstTransport.connectionWithDirection(
                first.peerId.toBase58() < second.peerId.toBase58()
            )
            val secondSurvivor = secondTransport.connectionWithDirection(
                second.peerId.toBase58() < first.peerId.toBase58()
            )

            assertThat(firstRejected.closeFuture().isDone).isFalse()
            assertThat(secondRejected.closeFuture().isDone).isFalse()
            assertThat(first.network.connect(second.peerId, second.listenAddresses().single()).get(10, TimeUnit.SECONDS))
                .describedAs("connection selected while the rejected connection is still finishing close")
                .isSameAs(firstSurvivor)
            assertThat(second.network.connect(first.peerId, first.listenAddresses().single()).get(10, TimeUnit.SECONDS))
                .describedAs("connection selected while the rejected connection is still finishing close")
                .isSameAs(secondSurvivor)
        } finally {
            firstCloseCompletion.complete(Unit)
            secondCloseCompletion.complete(Unit)
            firstDialGate.complete(Unit)
            secondDialGate.complete(Unit)
        }
    }

    private fun createHost(dialGate: CompletableFuture<Unit>): Pair<Host, GatedTcpTransport> {
        lateinit var transport: GatedTcpTransport
        val created = host {
            network {
                listen("/ip4/127.0.0.1/tcp/0")
            }
            transports {
                add { upgrader ->
                    GatedTcpTransport(upgrader, dialGate).also { transport = it }
                }
            }
            protocols {
                add(Ping())
            }
        }
        hosts += created
        created.start().get(30, TimeUnit.SECONDS)
        return created to transport
    }

    private fun createDelayedCloseHost(
        dialGate: CompletableFuture<Unit>,
        closeCompletion: CompletableFuture<Unit>
    ): Pair<Host, DelayedCloseTcpTransport> {
        lateinit var transport: DelayedCloseTcpTransport
        val created = host {
            network {
                listen("/ip4/127.0.0.1/tcp/0")
            }
            transports {
                add { upgrader ->
                    DelayedCloseTcpTransport(upgrader, dialGate, closeCompletion).also { transport = it }
                }
            }
            protocols {
                add(Ping())
            }
        }
        hosts += created
        created.start().get(30, TimeUnit.SECONDS)
        return created to transport
    }

    private fun activeConnectionCount(host: Host): Int =
        host.network.connections.count { !it.closeFuture().isDone }

    private fun assertPreferredConnectSelection(host: Host, remote: Host) {
        val expectedInitiator = host.peerId.toBase58() < remote.peerId.toBase58()
        val selected = host.network.connect(remote.peerId, remote.listenAddresses().single()).get()
        assertThat(selected.isInitiator)
            .describedAs("connection selected for a new call after simultaneous dial arbitration")
            .isEqualTo(expectedInitiator)
    }

    private fun awaitNonPreferredConnectionClose(host: Host, remotePeerId: PeerId) {
        val localPeerKeepsInitiator = host.peerId.toBase58() < remotePeerId.toBase58()
        host.network.connections
            .filter { connection ->
                connection.secureSession().remoteId == remotePeerId &&
                    connection.isInitiator != localPeerKeepsInitiator
            }
            .forEach { it.closeFuture().get(30, TimeUnit.SECONDS) }
    }

    private class GatedTcpTransport(
        upgrader: ConnectionUpgrader,
        private val dialGate: CompletableFuture<Unit>
    ) : TcpTransport(upgrader) {
        val dialCount = AtomicInteger()

        override fun dial(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Connection> {
            dialCount.incrementAndGet()
            return dialGate.thenCompose { super.dial(addr, connHandler, preHandler) }
        }
    }

    private class DelayedCloseTcpTransport(
        upgrader: ConnectionUpgrader,
        private val dialGate: CompletableFuture<Unit>,
        private val closeCompletion: CompletableFuture<Unit>
    ) : TcpTransport(upgrader) {
        private val wrappedConnections = CopyOnWriteArrayList<DelayedCloseConnection>()
        private val rejectedConnection = CompletableFuture<DelayedCloseConnection>()

        override fun listen(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Unit> =
            super.listen(addr, wrappingHandler(connHandler), preHandler)

        override fun dial(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Connection> =
            dialGate.thenCompose {
                super.dial(addr, wrappingHandler(connHandler), preHandler)
                    .thenApply { connection -> wrappedConnections.single { it.delegate === connection } }
            }

        fun awaitRejectedConnection(): DelayedCloseConnection =
            rejectedConnection.get(10, TimeUnit.SECONDS)

        fun connectionWithDirection(initiator: Boolean): DelayedCloseConnection =
            wrappedConnections.single { it.isInitiator == initiator }

        private fun wrappingHandler(handler: ConnectionHandler) =
            ConnectionHandler.create { connection ->
                val wrapped = DelayedCloseConnection(connection, closeCompletion) {
                    rejectedConnection.complete(it)
                }
                wrappedConnections += wrapped
                handler.handleConnection(wrapped)
            }
    }

    private class DelayedCloseConnection(
        val delegate: Connection,
        closeCompletion: CompletableFuture<Unit>,
        private val onCloseRequested: (DelayedCloseConnection) -> Unit
    ) : Connection by delegate {
        val closeRequested = CompletableFuture<Unit>()
        private val visibleCloseFuture = delegate.closeFuture().thenCompose { closeCompletion }

        override fun close(): CompletableFuture<Unit> {
            closeRequested.complete(Unit)
            onCloseRequested(this)
            delegate.close()
            return visibleCloseFuture
        }

        override fun closeFuture(): CompletableFuture<Unit> = visibleCloseFuture
    }

    companion object {
        private const val PING_PROTOCOL = "/ipfs/ping/1.0.0"
    }
}
