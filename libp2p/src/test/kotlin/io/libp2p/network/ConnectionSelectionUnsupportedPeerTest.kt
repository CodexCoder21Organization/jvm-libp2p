package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val ABSENT_SELECTION = "/libp2p/connection-selection/1.0.0"

class ConnectionSelectionUnsupportedPeerTest {
    @Test
    fun `thirty duplicate dials learn absence once and preserve eligible parents`() {
        UnsupportedFixture(31).use { fixture ->
            val peer = fixture.peer()
            val parents = fixture.burst(peer, 31)
            fixture.awaitControls()
            assertThat(fixture.incoming.get()).describedAs("Negotiation streams received by the real unsupported peer").isEqualTo(1)
            fixture.assertAbsenceLog(peer)
            assertThat(parents.count { !it.closeFuture().isDone }).isEqualTo(31)
            val probed = fixture.controlParents.single()
            val preserved = parents.last { it !== probed && it !== parents.first() }
            probed.close().get(10, TimeUnit.SECONDS)
            // Knowledge survives the loss of the parent on which it was learned.
            // Release uses serially: each release observes the absence learned by the first probe.
            repeat(30) {
                val hold = fixture.a.network.holdConnection(preserved).get(10, TimeUnit.SECONDS)
                assertThat(hold).isNotNull()
                hold!!.close()
            }
            fixture.awaitControls()
            assertThat(fixture.incoming.get()).isEqualTo(1)
            fixture.assertAbsenceLog(peer)
            assertThat(fixture.a.network.reserveConnection(preserved).get(10, TimeUnit.SECONDS)).isTrue()
            val echo = fixture.a.newStream<EchoController>(listOf("/test/echo"), preserved)
            assertThat(echo.controller.get(10, TimeUnit.SECONDS).echo("preserved duplicate").get(10, TimeUnit.SECONDS))
                .isEqualTo("preserved duplicate")
        }
    }

    @Test
    fun `last close forgets absence and an upgraded peer can retire duplicates`() {
        UnsupportedFixture(3).use { fixture ->
            val peer = fixture.peer()
            fixture.burst(peer, 3)
            fixture.awaitControls()
            assertThat(fixture.incoming.get()).isEqualTo(1)
            fixture.assertAbsenceLog(peer)
            fixture.closeConnections(peer)
            peer.stop().get(10, TimeUnit.SECONDS)
            val upgraded = fixture.peer(support = true, key = peer.privKey)
            assertThat(upgraded.peerId).isEqualTo(peer.peerId)
            val before = fixture.incoming.get()
            val parents = fixture.burst(upgraded, 3)
            fixture.awaitControls()
            parents.drop(1).forEach { it.closeFuture().get(10, TimeUnit.SECONDS) }
            assertThat(fixture.incoming.get() - before).isEqualTo(2)
            assertThat(parents.first().closeFuture().isDone).isFalse()
            assertThat(fixture.a.network.connect(peer.peerId).get(10, TimeUnit.SECONDS)).isSameAs(parents.first())
        }
    }

    @Test
    fun `a real stream reset is not remembered as protocol absence`() {
        UnsupportedFixture(3).use { fixture ->
            val peer = fixture.peer(reset = true)
            val parents = fixture.burst(peer, 3)
            fixture.awaitControls()
            assertThat(fixture.incoming.get()).isEqualTo(2)
            val before = fixture.incoming.get()
            fixture.a.network.holdConnection(parents.last()).get(10, TimeUnit.SECONDS)!!.close()
            fixture.awaitControls()
            assertThat(fixture.incoming.get()).isEqualTo(before + 1)
            assertThat(fixture.events.filter { it.level == Level.INFO }).isEmpty()
            assertThat(fixture.events).hasSize(3)
            assertThat(fixture.events.all { it.level == Level.WARN && it.thrown != null }).isTrue()
            parents.forEach { assertThat(fixture.a.network.reserveConnection(it).get(10, TimeUnit.SECONDS)).isTrue() }
        }
    }

    @Test
    fun `a supporting peer receives another request before its first retirement reply`() {
        UnsupportedFixture(3).use { fixture ->
            val reply = CompletableFuture<Unit>()
            val requests = LinkedBlockingQueue<Stream>()
            val peer = fixture.peer(replyGate = reply, requests = requests)
            try {
                fixture.burst(peer, 3)
                assertThat(requests.poll(10, TimeUnit.SECONDS)).isNotNull()
                assertThat(requests.poll(10, TimeUnit.SECONDS))
                    .describedAs("A supporting peer must receive the second retirement request while its first reply is held")
                    .isNotNull()
                assertThat(reply.isDone).isFalse()
            } finally {
                reply.complete(Unit)
            }
            fixture.awaitControls()
            assertThat(fixture.incoming.get()).isEqualTo(2)
        }
    }

    @Test
    fun `twenty unsupported and twenty supporting peers have independent connection lifetimes`() {
        UnsupportedFixture(3).use { fixture ->
            val unsupported = List(20) { fixture.peer() }
            val supporting = List(20) { fixture.peer(support = true) }
            val futures = (unsupported + supporting).map { peer ->
                CompletableFuture.supplyAsync { peer to fixture.burst(peer, 3) }
            }
            val groups = futures.map { it.get(10, TimeUnit.SECONDS) }
            fixture.awaitControls()
            groups.drop(20).forEach { (_, parents) -> parents.drop(1).forEach { it.closeFuture().get(10, TimeUnit.SECONDS) } }
            assertThat(fixture.incoming.get()).isEqualTo(20 + 40)
            unsupported.forEach { fixture.assertAbsenceLog(it) }
            groups.take(20).forEach { (_, parents) ->
                parents.forEach { assertThat(fixture.a.network.reserveConnection(it).get(10, TimeUnit.SECONDS)).isTrue() }
            }
            (unsupported + supporting).forEach { fixture.closeConnections(it) }
            assertThat(fixture.a.network.connections).isEmpty()
            val replacements = (unsupported + supporting).map { peer ->
                peer.stop().get(10, TimeUnit.SECONDS)
                fixture.peer(support = true, key = peer.privKey).also { assertThat(it.peerId).isEqualTo(peer.peerId) }
            }
            val before = fixture.incoming.get()
            val reconnects = replacements.map { peer ->
                CompletableFuture.supplyAsync { fixture.burst(peer, 3) }
            }.map { it.get(10, TimeUnit.SECONDS) }
            fixture.awaitControls()
            reconnects.forEach { parents -> parents.drop(1).forEach { it.closeFuture().get(10, TimeUnit.SECONDS) } }
            assertThat(fixture.incoming.get() - before).isEqualTo(80)
            replacements.forEach { fixture.closeConnections(it) }
            assertThat(fixture.a.network.connections).isEmpty()
        }
    }
}

private class UnsupportedFixture(private val width: Int) : AutoCloseable {
    val incoming = AtomicInteger()
    val events = CopyOnWriteArrayList<LogEvent>()
    private val logger = LogManager.getLogger("io.libp2p.network.ConnectionSelection") as Logger
    private val appender = object : AbstractAppender("selection-absence", null, null, false, null) {
        override fun append(event: LogEvent) {
            events.add(event.toImmutable())
        }
    }
    private val peers = CopyOnWriteArrayList<Host>()
    private val controls = CopyOnWriteArrayList<CompletableFuture<Unit>>()
    val controlParents = CopyOnWriteArrayList<Connection>()
    private lateinit var transport: UnsupportedBurstTransport
    val a = host {
        transports { add { upgrader -> UnsupportedBurstTransport(upgrader).also { transport = it } } }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        protocols { add(Echo()) }
    }

    init {
        appender.start()
        logger.addAppender(appender)
        a.addStreamVisitor(
            ChannelVisitor { stream ->
                val closed = CompletableFuture<Unit>()
                controls.add(closed)
                controlParents.add(stream.connection)
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun channelUnregistered(ctx: ChannelHandlerContext) {
                        ctx.fireChannelUnregistered()
                        ctx.executor().execute { closed.complete(Unit) }
                    }
                })
            }
        )
        a.start().get(10, TimeUnit.SECONDS)
    }

    fun peer(
        support: Boolean = false,
        reset: Boolean = false,
        key: PrivKey? = null,
        replyGate: CompletableFuture<Unit>? = null,
        requests: LinkedBlockingQueue<Stream> = LinkedBlockingQueue()
    ): Host {
        val peer = host {
            if (key != null) identity { factory = { key } }
            network { repeat(width) { listen("/ip4/127.0.0.${it + 1}/tcp/0") } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers {
                add(
                    StreamMuxerProtocol { multistream, protocols ->
                        val offered = if (support) {
                            protocols
                        } else {
                            protocols.filterNot {
                                it.protocolDescriptor.matchesAny(listOf(ABSENT_SELECTION))
                            }
                        }
                        val resetBinding = object : StrictProtocolBinding<Unit>(
                            ABSENT_SELECTION,
                            object : ProtocolHandler<Unit>(1, 1) {
                                override fun onStartResponder(stream: Stream): CompletableFuture<Unit> {
                                    stream.reset()
                                    return CompletableFuture.completedFuture(Unit)
                                }
                            }
                        ) {}
                        val requestCount = AtomicInteger()
                        val heldBinding = object : StrictProtocolBinding<Unit>(
                            ABSENT_SELECTION,
                            object : ProtocolHandler<Unit>(1, 1) {
                                override fun onStartResponder(stream: Stream): CompletableFuture<Unit> {
                                    stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
                                        override fun onMessage(stream: Stream, msg: ByteBuf) {
                                            assertThat(msg.readableBytes()).isEqualTo(1)
                                            assertThat(msg.readUnsignedByte().toInt()).isEqualTo(2)
                                            requests.add(stream)
                                            val ready = if (requestCount.incrementAndGet() == 1) replyGate!! else CompletableFuture.completedFuture(Unit)
                                            ready.thenRun { stream.writeAndFlush(Unpooled.buffer(1).writeByte(0)) }
                                        }
                                    })
                                    return CompletableFuture.completedFuture(Unit)
                                }
                            }
                        ) {}
                        val bindings = when {
                            replyGate != null -> offered + heldBinding
                            reset -> offered + resetBinding
                            else -> offered
                        }
                        StreamMuxerProtocol.getYamux().createMuxer(multistream, bindings)
                    }
                )
            }
            protocols { add(Echo()) }
        }
        peers.add(peer)
        peer.addStreamVisitor(ChannelVisitor { if (!it.isInitiator) incoming.incrementAndGet() })
        peer.start().get(10, TimeUnit.SECONDS)
        return peer
    }

    fun burst(peer: Host, size: Int): List<Connection> {
        val batch = transport.prepare(peer, size)
        val result = a.network.connect(peer.peerId, *peer.listenAddresses().toTypedArray())
        val parents = batch.ready.get(10, TimeUnit.SECONDS)
        batch.results.getValue(parents.first()).complete(parents.first())
        assertThat(result.get(10, TimeUnit.SECONDS)).isSameAs(parents.first())
        parents.drop(1).forEach { parent -> batch.results.getValue(parent).complete(parent) }
        return parents
    }

    fun awaitControls() {
        // A dial result schedules child creation on its parent's loop. Drain that scheduling
        // before capturing the children, then await their actual protocol teardown.
        var observed = -1
        while (observed != controls.size) {
            observed = controls.size
            a.network.connections.toList().forEach {
                (it as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
            }
            controls.toList().forEach { it.get(10, TimeUnit.SECONDS) }
        }
    }

    fun assertAbsenceLog(peer: Host) {
        val relevant = events.filter { it.message.formattedMessage.contains(peer.peerId.toString()) }
        assertThat(relevant).hasSize(1)
        assertThat(relevant.single().level).isEqualTo(Level.INFO)
        assertThat(relevant.single().message.formattedMessage)
            .isEqualTo("Peer ${peer.peerId} does not support $ABSENT_SELECTION; keeping duplicate connections open and eligible")
        assertThat(relevant.single().thrown).isNull()
    }

    fun closeConnections(peer: Host) {
        val local = a.network.connections.filter { it.secureSession().remoteId == peer.peerId }
        val remote = peer.network.connections.toList()
        (local + remote).map { it.close() }.forEach { it.get(10, TimeUnit.SECONDS) }
        // The physical close result can precede public close notification. Wait for that
        // notification and drain its event-loop callbacks before asserting table cleanup.
        (local + remote).forEach {
            it.closeFuture().get(10, TimeUnit.SECONDS)
            (it as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
        }
        assertThat(peer.network.connections).isEmpty()
        assertThat(a.network.connections.filter { it.secureSession().remoteId == peer.peerId }).isEmpty()
    }

    override fun close() {
        try {
            a.stop().get(10, TimeUnit.SECONDS)
        } finally {
            try {
                peers.forEach { it.stop().get(10, TimeUnit.SECONDS) }
                (listOf(a) + peers).flatMap { it.network.transports }.forEach {
                    assertThat(it.activeConnections).isZero()
                    assertThat(it.activeListeners).isZero()
                }
            } finally {
                logger.removeAppender(appender)
                appender.stop()
            }
        }
    }
}

private class UnsupportedBatch(val size: Int) {
    val ready = CompletableFuture<List<Connection>>()
    val start = CompletableFuture<Unit>()
    val parents = CopyOnWriteArrayList<Connection>()
    val results = java.util.concurrent.ConcurrentHashMap<Connection, CompletableFuture<Connection>>()
    val count = AtomicInteger()
}

private class UnsupportedBurstTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    private val batches = java.util.concurrent.ConcurrentHashMap<String, UnsupportedBatch>()
    fun prepare(peer: Host, size: Int): UnsupportedBatch = UnsupportedBatch(size).also { batches[peer.peerId.toString()] = it }
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> {
        val batch = batches.getValue(addr.getPeerId()!!.toString())
        val result = CompletableFuture<Connection>()
        batch.start.thenCompose { super.dial(addr, connHandler, preHandler) }.whenComplete { parent, failure ->
            if (failure != null) {
                batch.ready.completeExceptionally(failure)
                result.completeExceptionally(failure)
            } else {
                batch.results[parent] = result
                batch.parents.add(parent)
                if (batch.parents.size == batch.size) batch.ready.complete(batch.parents.toList())
            }
        }
        if (batch.count.incrementAndGet() == batch.size) batch.start.complete(Unit)
        return result
    }
}
