package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val RETIREMENT_PROTOCOL = "/libp2p/connection-selection/1.0.0"

class NetworkRetirementPendingCallerTest {
    @Test
    fun `a delayed caller completes before a pending retirement is denied`() =
        verifyPendingRetirementCaller {
            it.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(0)).thenCompose { _ -> it.closeWrite() }
        }

    @Test
    fun `a delayed caller completes before a pending retirement ends writing`() =
        verifyPendingRetirementCaller { it.closeWrite() }

    @Test
    fun `a delayed caller completes before a pending retirement is reset`() =
        verifyPendingRetirementCaller { it.reset() }

    @Test
    fun `a delayed caller completes before a pending retirement parent closes`() =
        verifyPendingRetirementCaller(parentCloses = true) { it.connection.close() }
}

private fun verifyPendingRetirementCaller(parentCloses: Boolean = false, endRequest: (Stream) -> Unit) {
    val request = CompletableFuture<Stream>()
    val replyGate = CompletableFuture<Unit>()
    val releaseExecutor = Executors.newSingleThreadExecutor()
    val b = host {
        network {
            listen("/ip4/127.0.0.1/tcp/0")
            listen("/ip4/127.0.0.2/tcp/0")
        }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers {
            add(
                StreamMuxerProtocol { multistream, protocols ->
                    val responder = object : ProtocolHandler<Unit>(1, 1) {
                        override fun onStartResponder(stream: Stream): CompletableFuture<Unit> {
                            stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
                                override fun onMessage(stream: Stream, msg: ByteBuf) {
                                    assertThat(msg.readableBytes()).isEqualTo(1)
                                    assertThat(msg.readUnsignedByte().toInt()).isEqualTo(2)
                                    request.complete(stream)
                                    replyGate.thenRun { endRequest(stream) }
                                }
                            })
                            return CompletableFuture.completedFuture(Unit)
                        }
                    }
                    StreamMuxerProtocol.getYamux().createMuxer(
                        multistream,
                        protocols.filterNot { it.protocolDescriptor.matchesAny(listOf(RETIREMENT_PROTOCOL)) } +
                            object : StrictProtocolBinding<Unit>(RETIREMENT_PROTOCOL, responder) {}
                    )
                }
            )
        }
        protocols {
            add(Ping())
            add(Echo())
        }
    }
    lateinit var transport: PendingCallerTransport
    val a = host {
        transports { add { upgrader -> PendingCallerTransport(upgrader).also { transport = it } } }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        protocols {
            add(Ping())
            add(Echo())
        }
    }
    val controlTerminated = CompletableFuture<Unit>()
    val parentTerminated = CompletableFuture<Unit>()
    val control = CompletableFuture<Stream>()
    a.addStreamVisitor(
        ChannelVisitor { stream ->
            if (transport.first.isDone && stream.connection === transport.first.get()) {
                control.complete(stream)
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun channelUnregistered(ctx: ChannelHandlerContext) {
                        ctx.fireChannelUnregistered()
                        ctx.executor().execute { controlTerminated.complete(Unit) }
                    }
                })
            }
        }
    )
    try {
        a.start().get(10, TimeUnit.SECONDS)
        b.start().get(10, TimeUnit.SECONDS)
        val addresses = b.listenAddresses()
        val lateOnly = a.network.connect(b.peerId, addresses[0])
        val winnerOnly = a.network.connect(b.peerId, addresses[1])
        val selected = winnerOnly.get(10, TimeUnit.SECONDS)
        val unused = transport.first.get(10, TimeUnit.SECONDS)
        assertThat(selected).isNotSameAs(unused)
        assertThat(selected).isSameAs(transport.second.get(10, TimeUnit.SECONDS))
        assertThat(lateOnly.isDone).isFalse()
        unused.pushHandler(object : ChannelInboundHandlerAdapter() {
            override fun channelUnregistered(ctx: ChannelHandlerContext) {
                ctx.fireChannelUnregistered()
                ctx.executor().execute { parentTerminated.complete(Unit) }
            }
        })
        val listenersBefore = unused.closeFuture().numberOfDependents
        val released = CompletableFuture.runAsync({ transport.releaseFirst.complete(Unit) }, releaseExecutor)
        val remoteChild = request.get(10, TimeUnit.SECONDS)
        val localChild = control.get(10, TimeUnit.SECONDS)
        assertThat(lateOnly.get(10, TimeUnit.SECONDS)).isSameAs(selected)
        released.get(10, TimeUnit.SECONDS)
        assertThat(a.network.connect(b.peerId).get(10, TimeUnit.SECONDS)).isSameAs(selected)
        assertThat(localChild.connection).isSameAs(unused)
        assertThat(localChild.closeFuture().isDone).isFalse()
        assertThat(remoteChild.closeFuture().isDone).isFalse()
        assertThat(replyGate.isDone).isFalse()
        val pendingEcho = a.newStream<EchoController>(listOf("/test/echo"), selected)
            .controller.get(10, TimeUnit.SECONDS)
        assertThat(pendingEcho.echo("Echo while retirement is pending").get(10, TimeUnit.SECONDS))
            .isEqualTo("Echo while retirement is pending")
        assertThat(localChild.closeFuture().isDone).isFalse()
        assertThat(remoteChild.closeFuture().isDone).isFalse()
        assertThat(replyGate.isDone).isFalse()
        replyGate.complete(Unit)
        localChild.closeFuture().get(10, TimeUnit.SECONDS)
        remoteChild.closeFuture().get(10, TimeUnit.SECONDS)
        controlTerminated.get(10, TimeUnit.SECONDS)
        assertThat(lateOnly.isDone).isTrue()
        assertThat(a.network.connect(b.peerId).get(10, TimeUnit.SECONDS)).isSameAs(selected)
        val ping = a.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), selected)
            .controller.get(10, TimeUnit.SECONDS)
        ping.ping().get(10, TimeUnit.SECONDS)
        assertThat(unused.closeFuture().numberOfDependents).isLessThanOrEqualTo(listenersBefore)
        if (parentCloses) {
            unused.closeFuture().get(10, TimeUnit.SECONDS)
            parentTerminated.get(10, TimeUnit.SECONDS)
            assertThat(a.network.connections).doesNotContain(unused)
            assertThat(transport.activeConnections).isEqualTo(1)
        } else {
            assertThat(unused.closeFuture().isDone).isFalse()
            assertThat(a.network.connections).contains(unused)
            assertThat(transport.activeConnections).isEqualTo(2)
            // A failed retirement cannot exclude a live, possibly published remote parent.
            selected.close().get(10, TimeUnit.SECONDS)
            assertThat(a.network.connect(b.peerId).get(10, TimeUnit.SECONDS)).isSameAs(unused)
            val recovered = a.newStream<EchoController>(listOf("/test/echo"), unused)
                .controller.get(10, TimeUnit.SECONDS)
            assertThat(recovered.echo("recovered retirement parent").get(10, TimeUnit.SECONDS))
                .isEqualTo("recovered retirement parent")
        }
    } finally {
        replyGate.complete(Unit)
        transport.start.complete(Unit)
        transport.releaseFirst.complete(Unit)
        releaseExecutor.shutdown()
        assertThat(releaseExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
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

private class PendingCallerTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val first = CompletableFuture<Connection>()
    val second = CompletableFuture<Connection>()
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
                first.thenCompose { super.dial(addr, connHandler, preHandler) }.thenApply { connection ->
                    second.complete(connection)
                    connection
                }
            }
            else -> error("Expected only two genuine transport dials")
        }
}
