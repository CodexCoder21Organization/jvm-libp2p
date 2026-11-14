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

class ConnectionSelectionLifetimeTest {
    @Test
    fun `remote last close asks an unsupported peer again`() {
        checkLifetimeClose("remote")
    }

    @Test
    fun `local last close asks an unsupported peer again`() {
        checkLifetimeClose("local")
    }

    @Test
    fun `transport error last close asks an unsupported peer again`() {
        checkLifetimeClose("error")
    }

    @Test
    fun `node shutdown asks the same identity again`() {
        LifetimeFixture(2).use { fixture ->
            val peer = fixture.peer()
            val parents = fixture.burst(peer, 2)
            fixture.awaitControls()
            assertThat(fixture.incoming.get()).isEqualTo(1)
            val remote = peer.network.connections.toList()
            fixture.a.stop().get(10, TimeUnit.SECONDS)
            parents.forEach { it.closeFuture().get(10, TimeUnit.SECONDS) }
            remote.forEach {
                it.closeFuture().get(10, TimeUnit.SECONDS)
                (it as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
            }
            assertThat(fixture.a.network.connections).isEmpty()
            assertThat(peer.network.connections).isEmpty()
            LifetimeFixture(2, fixture.a.privKey).use { restarted ->
                assertThat(restarted.a.peerId).isEqualTo(fixture.a.peerId)
                val reconnected = restarted.burst(peer, 2)
                restarted.awaitControls()
                assertThat(fixture.incoming.get()).isEqualTo(2)
                assertThat(reconnected.all { !it.closeFuture().isDone }).isTrue()
            }
        }
    }

    @Test
    fun `two thousand disconnected unsupported identities are each asked again once`() {
        LifetimeFixture(2).use { fixture ->
            // Serial on purpose: measure accumulated disconnected history in one surviving network.
            repeat(2000) { index ->
                val peer = fixture.peer()
                fixture.burst(peer, 2)
                fixture.awaitControls()
                assertThat(fixture.incoming.get()).describedAs("first lifetime $index").isEqualTo(index * 2 + 1)
                fixture.assertAbsenceLog(peer)
                fixture.closeConnections(peer)
                val parents = fixture.burst(peer, 2)
                fixture.awaitControls()
                assertThat(fixture.incoming.get()).describedAs("reconnected lifetime $index").isEqualTo(index * 2 + 2)
                assertThat(parents.all { !it.closeFuture().isDone }).isTrue()
                fixture.closeConnections(peer)
                assertThat(fixture.a.network.connections).isEmpty()
                fixture.releasePeer(peer)
            }
            assertThat(fixture.incoming.get()).isEqualTo(4000)
        }
    }
}

private fun checkLifetimeClose(mode: String) {
    LifetimeFixture(2).use { fixture ->
        val old = fixture.peer()
        val parents = fixture.burst(old, 2)
        fixture.awaitControls()
        assertThat(fixture.incoming.get()).isEqualTo(1)
        val remote = old.network.connections.toList()
        val errors = LinkedBlockingQueue<Throwable>()
        if (mode == "error") {
            parents.forEach {
                (it as ConnectionOverNetty).nettyChannel.pipeline().addFirst(object : ChannelInboundHandlerAdapter() {
                    @Deprecated("Deprecated in Java")
                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                        errors.add(cause)
                        ctx.fireExceptionCaught(cause)
                    }
                })
                it.nettyChannel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
            }
        }
        when (mode) {
            "local" -> parents.map { it.close() }.forEach { it.get(10, TimeUnit.SECONDS) }
            "remote" -> remote.map { it.close() }.forEach { it.get(10, TimeUnit.SECONDS) }
            "error" -> remote.forEach {
                // A real TCP reset produces the local transport-error close path.
                (it as ConnectionOverNetty).nettyChannel.config().setOption(io.netty.channel.ChannelOption.SO_LINGER, 0)
                it.close().get(10, TimeUnit.SECONDS)
            }
            else -> error("Unknown close path: $mode")
        }
        (parents + remote).forEach {
            it.closeFuture().get(10, TimeUnit.SECONDS)
            (it as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
        }
        if (mode == "error") {
            repeat(2) {
                assertThat(errors.poll(10, TimeUnit.SECONDS)).isInstanceOf(java.io.IOException::class.java)
            }
        }
        assertThat(fixture.a.network.connections).isEmpty()
        assertThat(old.network.connections).isEmpty()
        val peer = old
        val reconnected = fixture.burst(peer, 2)
        assertThat(reconnected.first().secureSession().remoteId).isEqualTo(old.peerId)
        fixture.awaitControls()
        assertThat(fixture.incoming.get()).isEqualTo(2)
        assertThat(reconnected.all { !it.closeFuture().isDone }).isTrue()
        val echo = fixture.a.newStream<EchoController>(listOf("/test/echo"), reconnected.first())
        assertThat(echo.controller.get(10, TimeUnit.SECONDS).echo("reconnected $mode").get(10, TimeUnit.SECONDS))
            .isEqualTo("reconnected $mode")
    }
}

private class LifetimeFixture(private val width: Int, key: PrivKey? = null) : AutoCloseable {
    val incoming = AtomicInteger()
    val events = CopyOnWriteArrayList<LogEvent>()
    private val logger = LogManager.getLogger("io.libp2p.network.ConnectionSelection") as Logger
    private val appender = object : AbstractAppender("selection-lifetime-${java.util.UUID.randomUUID()}", null, null, false, null) {
        override fun append(event: LogEvent) {
            events.add(event.toImmutable())
        }
    }
    private val peers = CopyOnWriteArrayList<Host>()
    private val controls = CopyOnWriteArrayList<CompletableFuture<Unit>>()
    val controlParents = CopyOnWriteArrayList<Connection>()
    private lateinit var transport: LifetimeBurstTransport
    val a = host {
        if (key != null) identity { factory = { key } }
        transports { add { upgrader -> LifetimeBurstTransport(upgrader).also { transport = it } } }
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

    fun releasePeer(peer: Host) {
        peer.stop().get(10, TimeUnit.SECONDS)
        peer.network.transports.forEach {
            assertThat(it.activeConnections).isZero()
            assertThat(it.activeListeners).isZero()
        }
        peers.remove(peer)
        transport.forget(peer)
        controls.clear()
        controlParents.clear()
        events.clear()
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

private class LifetimeBatch(val size: Int) {
    val ready = CompletableFuture<List<Connection>>()
    val start = CompletableFuture<Unit>()
    val parents = CopyOnWriteArrayList<Connection>()
    val results = java.util.concurrent.ConcurrentHashMap<Connection, CompletableFuture<Connection>>()
    val count = AtomicInteger()
}

private class LifetimeBurstTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    private val batches = java.util.concurrent.ConcurrentHashMap<String, LifetimeBatch>()
    fun prepare(peer: Host, size: Int): LifetimeBatch = LifetimeBatch(size).also { batches[peer.peerId.toString()] = it }
    fun forget(peer: Host) {
        batches.remove(peer.peerId.toString())
    }
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
