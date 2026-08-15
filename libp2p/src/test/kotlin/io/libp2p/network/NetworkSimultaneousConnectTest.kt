package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingBinding
import io.libp2p.protocol.PingController
import io.libp2p.protocol.PingProtocol
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
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

    @Test
    fun `rejected address result fails when its selected survivor has closed`() {
        val firstDialGate = CompletableFuture<Unit>()
        val secondDialGate = CompletableFuture<Unit>()
        val firstCloseCompletion = CompletableFuture<Unit>()
        val secondCloseCompletion = CompletableFuture<Unit>()
        val firstDialCompletion = CompletableFuture<Unit>()
        val secondDialCompletion = CompletableFuture<Unit>()
        val (first, firstTransport) = createDelayedCloseHost(
            firstDialGate,
            firstCloseCompletion,
            firstDialCompletion
        )
        val (second, secondTransport) = createDelayedCloseHost(
            secondDialGate,
            secondCloseCompletion,
            secondDialCompletion
        )
        val lower = if (first.peerId.toBase58() < second.peerId.toBase58()) first else second
        val higher = if (lower === first) second else first
        val higherTransport = if (higher === first) firstTransport else secondTransport
        val lowerDialGate = if (lower === first) firstDialGate else secondDialGate
        val higherDialGate = if (higher === first) firstDialGate else secondDialGate
        val higherDialCompletion = if (higher === first) firstDialCompletion else secondDialCompletion
        val lowerDialCompletion = if (lower === first) firstDialCompletion else secondDialCompletion

        val higherConnect = higher.network.connect(lower.peerId, lower.listenAddresses().single())
        val lowerConnect = lower.network.connect(higher.peerId, higher.listenAddresses().single())

        try {
            // Hold the higher peer's transport result after its nonpreferred candidate has passed
            // the connection hook. The preferred lower-to-higher candidate can then replace it.
            higherDialGate.complete(Unit)
            higherTransport.awaitConnection(true)
            lowerDialGate.complete(Unit)
            val survivor = higherTransport.awaitConnection(false)
            higherTransport.awaitRejectedConnection()

            survivor.close()
            firstCloseCompletion.complete(Unit)
            secondCloseCompletion.complete(Unit)
            survivor.closeFuture().get(10, TimeUnit.SECONDS)

            higherDialCompletion.complete(Unit)
            val error = org.junit.jupiter.api.assertThrows<ExecutionException> {
                higherConnect.get(10, TimeUnit.SECONDS)
            }
            assertThat(rootCause(error)).isInstanceOf(ConnectionClosedException::class.java)
            assertThat(rootCause(error).message)
                .contains("candidate was rejected or closed and no active settled connection remains")
        } finally {
            lowerDialCompletion.complete(Unit)
            firstDialGate.complete(Unit)
            secondDialGate.complete(Unit)
            firstCloseCompletion.complete(Unit)
            secondCloseCompletion.complete(Unit)
            runCatching { lowerConnect.get(10, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `stream on provisional connection closes and a later stream uses the survivor`() {
        val firstDialGate = CompletableFuture<Unit>()
        val secondDialGate = CompletableFuture<Unit>()
        val firstCloseCompletion = CompletableFuture<Unit>()
        val secondCloseCompletion = CompletableFuture<Unit>()
        val firstPing = ControlledPingProtocol()
        val secondPing = ControlledPingProtocol()
        val (first, firstTransport) = createDelayedCloseHost(
            firstDialGate,
            firstCloseCompletion,
            ping = PingBinding(firstPing)
        )
        val (second, secondTransport) = createDelayedCloseHost(
            secondDialGate,
            secondCloseCompletion,
            ping = PingBinding(secondPing)
        )
        val lower = if (first.peerId.toBase58() < second.peerId.toBase58()) first else second
        val higher = if (lower === first) second else first
        val lowerDialGate = if (lower === first) firstDialGate else secondDialGate
        val higherDialGate = if (higher === first) firstDialGate else secondDialGate
        val higherTransport = if (higher === first) firstTransport else secondTransport
        val lowerPing = if (lower === first) firstPing else secondPing

        try {
            val preferredConnect = lower.network.connect(
                higher.peerId,
                higher.listenAddresses().single()
            )
            val provisionalStream = higher.newStream<PingController>(
                listOf(PING_PROTOCOL),
                lower.peerId,
                lower.listenAddresses().single()
            )
            higherDialGate.complete(Unit)
            val provisionalPing = provisionalStream.controller.get(30, TimeUnit.SECONDS).ping()
            lowerPing.firstRequest.get(10, TimeUnit.SECONDS)

            lowerDialGate.complete(Unit)
            preferredConnect.get(30, TimeUnit.SECONDS)
            higherTransport.awaitConnection(false)
            higherTransport.awaitRejectedConnection()

            val error = org.junit.jupiter.api.assertThrows<ExecutionException> {
                provisionalPing.get(10, TimeUnit.SECONDS)
            }
            assertThat(error.cause)
                .isExactlyInstanceOf(ConnectionClosedException::class.java)
                .hasMessage("Connection is closed")

            firstPing.respond.set(true)
            secondPing.respond.set(true)
            higher.newStream<PingController>(
                listOf(PING_PROTOCOL),
                lower.peerId,
                lower.listenAddresses().single()
            ).controller.thenCompose { it.ping() }.get(30, TimeUnit.SECONDS)
        } finally {
            firstDialGate.complete(Unit)
            secondDialGate.complete(Unit)
            firstCloseCompletion.complete(Unit)
            secondCloseCompletion.complete(Unit)
        }
    }

    @Test
    fun `simultaneous multi-address connects retain the same physical connection`() {
        val (first, firstTransport) = createDeliveryControlledHost()
        val (second, secondTransport) = createDeliveryControlledHost()
        val lower = if (first.peerId.toBase58() < second.peerId.toBase58()) first else second
        val higher = if (lower === first) second else first
        val lowerTransport = if (lower === first) firstTransport else secondTransport
        val higherTransport = if (higher === first) firstTransport else secondTransport
        val higherAddresses = higher.listenAddresses().take(2)
        val lowerAddress = lower.listenAddresses().first()
        val higherInboundFirstGate = CompletableFuture<Unit>()
        val higherInboundSecondGate = CompletableFuture<Unit>()
        val lowerOutboundFirstGate = CompletableFuture<Unit>()
        val lowerOutboundSecondGate = CompletableFuture<Unit>()
        val higherOutboundGate = CompletableFuture<Unit>()
        val lowerInboundGate = CompletableFuture<Unit>()
        val gates = listOf(
            higherInboundFirstGate,
            higherInboundSecondGate,
            lowerOutboundFirstGate,
            lowerOutboundSecondGate,
            higherOutboundGate,
            lowerInboundGate
        )

        higherTransport.setInboundDeliveryGate(higherAddresses[0], higherInboundFirstGate)
        higherTransport.setInboundDeliveryGate(higherAddresses[1], higherInboundSecondGate)
        lowerTransport.setOutboundDeliveryGate(higherAddresses[0], lowerOutboundFirstGate)
        lowerTransport.setOutboundDeliveryGate(higherAddresses[1], lowerOutboundSecondGate)
        higherTransport.setOutboundDeliveryGate(lowerAddress, higherOutboundGate)
        lowerTransport.setInboundDeliveryGate(lowerAddress, lowerInboundGate)

        try {
            val lowerConnect = lower.network.connect(
                higher.peerId,
                *higherAddresses.toTypedArray()
            )
            val higherConnect = higher.network.connect(lower.peerId, lowerAddress)

            assertThat(lowerTransport.realDials).hasSize(2)
            assertThat(higherTransport.realDials).hasSize(1)

            // Give the higher peer the first inbound candidate while the lower peer settles on
            // the second outbound candidate. This deterministically makes the higher peer's mapped
            // inbound candidate lose the remote address race.
            higherInboundFirstGate.complete(Unit)
            lowerOutboundSecondGate.complete(Unit)
            val mappedInbound = higherTransport.awaitDelivery(false, higherAddresses[0])
            val lowerSurvivor = lowerTransport.awaitDelivery(true, higherAddresses[1])

            higherInboundSecondGate.complete(Unit)
            lowerOutboundFirstGate.complete(Unit)
            val higherSurvivor = higherTransport.awaitDelivery(false, higherAddresses[1])
            lowerTransport.awaitDelivery(true, higherAddresses[0])
            mappedInbound.closeFuture().get(30, TimeUnit.SECONDS)

            // The higher peer's outbound connection was already started. Deliver it only after
            // the mapped inbound candidate has closed; settlement must recover the remaining live
            // inbound candidate rather than accept this opposite-direction socket.
            higherOutboundGate.complete(Unit)
            assertThat(higherConnect.get(30, TimeUnit.SECONDS))
                .describedAs("higher peer connect result after its mapped inbound candidate closes")
                .isSameAs(higherSurvivor)

            lowerInboundGate.complete(Unit)
            lowerConnect.get(30, TimeUnit.SECONDS)
            CompletableFuture.allOf(
                *(lowerTransport.realDials + higherTransport.realDials).toTypedArray()
            ).get(30, TimeUnit.SECONDS)

            awaitNonSurvivorCloses(higherTransport.observedConnections, higherSurvivor)
            awaitNonSurvivorCloses(lowerTransport.observedConnections, lowerSurvivor)
            assertThat(higher.network.connections).containsExactly(higherSurvivor)
            assertThat(lower.network.connections).containsExactly(lowerSurvivor)
            assertThat(higherSurvivor.localAddress()).isEqualTo(lowerSurvivor.remoteAddress())
            assertThat(higherSurvivor.remoteAddress()).isEqualTo(lowerSurvivor.localAddress())
            assertThat(higher.network.connect(lower.peerId, lowerAddress).get(10, TimeUnit.SECONDS))
                .isSameAs(higherSurvivor)
            assertThat(lower.network.connect(higher.peerId, higherAddresses[1]).get(10, TimeUnit.SECONDS))
                .isSameAs(lowerSurvivor)

            higher.newStream<PingController>(
                listOf(PING_PROTOCOL),
                lower.peerId,
                lowerAddress
            ).controller.thenCompose { it.ping() }.get(30, TimeUnit.SECONDS)
            lower.newStream<PingController>(
                listOf(PING_PROTOCOL),
                higher.peerId,
                higherAddresses[1]
            ).controller.thenCompose { it.ping() }.get(30, TimeUnit.SECONDS)
        } finally {
            gates.forEach { it.complete(Unit) }
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
        closeCompletion: CompletableFuture<Unit>,
        dialCompletion: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit),
        ping: PingBinding = Ping()
    ): Pair<Host, DelayedCloseTcpTransport> {
        lateinit var transport: DelayedCloseTcpTransport
        val created = host {
            network {
                listen("/ip4/127.0.0.1/tcp/0")
            }
            transports {
                add { upgrader ->
                    DelayedCloseTcpTransport(upgrader, dialGate, closeCompletion, dialCompletion).also { transport = it }
                }
            }
            protocols {
                add(ping)
            }
        }
        hosts += created
        created.start().get(30, TimeUnit.SECONDS)
        return created to transport
    }

    private fun createDeliveryControlledHost(): Pair<Host, DeliveryControlledTcpTransport> {
        lateinit var transport: DeliveryControlledTcpTransport
        val created = host {
            network {
                listen("/ip4/127.0.0.1/tcp/0")
                listen("/ip4/127.0.0.2/tcp/0")
            }
            transports {
                add { upgrader -> DeliveryControlledTcpTransport(upgrader).also { transport = it } }
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
        val selected = host.network.connect(remote.peerId, remote.listenAddresses().single()).get(10, TimeUnit.SECONDS)
        assertThat(selected.isInitiator)
            .describedAs("connection selected for a new call after simultaneous dial arbitration")
            .isEqualTo(expectedInitiator)
    }

    private fun awaitNonSurvivorCloses(connections: List<Connection>, survivor: Connection) {
        connections.filterNot { it === survivor }
            .forEach { it.closeFuture().get(30, TimeUnit.SECONDS) }
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

    private fun rootCause(error: Throwable): Throwable =
        generateSequence(error) { it.cause }.last()

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
        private val closeCompletion: CompletableFuture<Unit>,
        private val dialCompletion: CompletableFuture<Unit>
    ) : TcpTransport(upgrader) {
        private val wrappedConnections = CopyOnWriteArrayList<DelayedCloseConnection>()
        private val rejectedConnection = CompletableFuture<DelayedCloseConnection>()
        private val directionConnections = ConcurrentHashMap<Boolean, CompletableFuture<DelayedCloseConnection>>()

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
                    .thenCompose { connection ->
                        val wrapped = wrappedConnections.single { it.delegate === connection }
                        dialCompletion.thenApply { wrapped }
                    }
            }

        fun awaitRejectedConnection(): DelayedCloseConnection =
            rejectedConnection.get(10, TimeUnit.SECONDS)

        fun connectionWithDirection(initiator: Boolean): DelayedCloseConnection =
            wrappedConnections.single { it.isInitiator == initiator }

        fun awaitConnection(initiator: Boolean): DelayedCloseConnection =
            directionConnections.computeIfAbsent(initiator) { CompletableFuture() }
                .get(30, TimeUnit.SECONDS)

        private fun wrappingHandler(handler: ConnectionHandler) =
            ConnectionHandler.create { connection ->
                val wrapped = DelayedCloseConnection(connection, closeCompletion) {
                    rejectedConnection.complete(it)
                }
                wrappedConnections += wrapped
                directionConnections.computeIfAbsent(wrapped.isInitiator) { CompletableFuture() }
                    .complete(wrapped)
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

    private class DeliveryControlledTcpTransport(
        upgrader: ConnectionUpgrader
    ) : TcpTransport(upgrader) {
        val realDials = CopyOnWriteArrayList<CompletableFuture<Connection>>()
        val observedConnections = CopyOnWriteArrayList<Connection>()
        private val outboundDeliveryGates = ConcurrentHashMap<Multiaddr, CompletableFuture<Unit>>()
        private val inboundDeliveryGates = ConcurrentHashMap<Multiaddr, CompletableFuture<Unit>>()
        private val deliveries = ConcurrentHashMap<DeliveryKey, CompletableFuture<Connection>>()

        fun setOutboundDeliveryGate(address: Multiaddr, gate: CompletableFuture<Unit>) {
            outboundDeliveryGates[transportAddress(address)] = gate
        }

        fun setInboundDeliveryGate(address: Multiaddr, gate: CompletableFuture<Unit>) {
            inboundDeliveryGates[transportAddress(address)] = gate
        }

        fun awaitDelivery(initiator: Boolean, address: Multiaddr): Connection =
            deliveryFuture(initiator, address).get(30, TimeUnit.SECONDS)

        override fun listen(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Unit> =
            super.listen(
                addr,
                ConnectionHandler.create { connection ->
                    val address = transportAddress(connection.localAddress())
                    deliver(
                        connection,
                        connHandler,
                        inboundDeliveryGates[address] ?: CompletableFuture.completedFuture(Unit),
                        DeliveryKey(false, address)
                    )
                },
                preHandler
            )

        override fun dial(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Connection> {
            val address = transportAddress(addr)
            val realDial = super.dial(addr, ConnectionHandler.create { }, preHandler)
            realDials += realDial
            return realDial.thenCompose { connection ->
                deliver(
                    connection,
                    connHandler,
                    outboundDeliveryGates[address] ?: CompletableFuture.completedFuture(Unit),
                    DeliveryKey(true, address)
                )
            }
        }

        private fun deliveryFuture(initiator: Boolean, address: Multiaddr): CompletableFuture<Connection> =
            deliveries.computeIfAbsent(DeliveryKey(initiator, transportAddress(address))) {
                CompletableFuture()
            }

        private fun deliver(
            connection: Connection,
            handler: ConnectionHandler,
            gate: CompletableFuture<Unit>,
            key: DeliveryKey
        ): CompletableFuture<Connection> {
            val delivered = deliveryFuture(key.initiator, key.address)
            gate.whenComplete { _, gateError ->
                if (gateError != null) {
                    delivered.completeExceptionally(gateError)
                } else {
                    try {
                        observedConnections += connection
                        handler.handleConnection(connection)
                        delivered.complete(connection)
                    } catch (error: Throwable) {
                        delivered.completeExceptionally(error)
                    }
                }
            }
            return delivered
        }

        private fun transportAddress(address: Multiaddr): Multiaddr =
            Multiaddr(address.components.filterNot { it.protocol in Protocol.PEER_ID_PROTOCOLS })

        private data class DeliveryKey(val initiator: Boolean, val address: Multiaddr)
    }

    private class ControlledPingProtocol : PingProtocol() {
        val firstRequest = CompletableFuture<Unit>()
        val respond = java.util.concurrent.atomic.AtomicBoolean()

        override fun onStartInitiator(stream: Stream): CompletableFuture<PingController> {
            val handler = ControlledPingInitiator()
            stream.pushHandler(handler)
            return handler.active
        }

        override fun onStartResponder(stream: Stream): CompletableFuture<PingController> {
            val handler = object : ProtocolMessageHandler<ByteBuf>, PingController {
                override fun onMessage(stream: Stream, msg: ByteBuf) {
                    if (respond.get()) {
                        stream.writeAndFlush(msg)
                    } else {
                        firstRequest.complete(Unit)
                    }
                }

                override fun ping(): CompletableFuture<Long> {
                    throw Libp2pException("This is ping responder only")
                }
            }
            stream.pushHandler(handler)
            return CompletableFuture.completedFuture(handler)
        }

        private class ControlledPingInitiator : ProtocolMessageHandler<ByteBuf>, PingController {
            val active = CompletableFuture<PingController>()
            private val response = CompletableFuture<Long>()
            private lateinit var stream: Stream

            override fun onActivated(stream: Stream) {
                this.stream = stream
                active.complete(this)
            }

            override fun onMessage(stream: Stream, msg: ByteBuf) {
                response.complete(1L)
            }

            override fun onClosed(stream: Stream) {
                response.completeExceptionally(ConnectionClosedException())
                active.completeExceptionally(ConnectionClosedException())
            }

            override fun ping(): CompletableFuture<Long> {
                stream.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(1)))
                return response
            }
        }
    }

    companion object {
        private const val PING_PROTOCOL = "/ipfs/ping/1.0.0"
    }
}
