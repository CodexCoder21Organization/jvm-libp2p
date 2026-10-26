package io.libp2p.reviewr84

import io.libp2p.core.*
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
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class R84RetirementLifecycleTest {
    @Test fun halfClose() = verifyEvent("halfClose") { it.closeWrite() }
    @Test fun fullClose() = verifyEvent("fullClose") { it.close() }
    @Test fun reset() = verifyEvent("reset") { it.reset() }
    @Test fun parentClose() = verifyEvent("parentClose") { it.connection.close() }
    @Test fun denial() = verifyEvent("denial") { it.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(0)).thenCompose { _ -> it.closeWrite() } }
    @Test fun malformed() = verifyEvent("malformed") { it.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(42)) }
    @Test fun shutdownPending() = verifyEvent("shutdown") { }
}

private fun verifyEvent(mode: String, terminal: (Stream) -> Unit) {
    lateinit var transport: LifecycleTransport
    val a = host {
        transports { add { LifecycleTransport(it).also { transport = it } } }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        protocols { add(Echo()) }
    }
    val request = CompletableFuture<Stream>()
    val b = host {
        network { listen("/ip4/127.0.0.1/tcp/0"); listen("/ip4/127.0.0.2/tcp/0") }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol { multistream, protocols ->
            val responder = object : ProtocolHandler<Unit>(1, 1) {
                override fun onStartResponder(stream: Stream): CompletableFuture<Unit> {
                    stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
                        override fun onMessage(stream: Stream, msg: ByteBuf) {
                            assertThat(msg.readableBytes()).isEqualTo(1)
                            assertThat(msg.readUnsignedByte().toInt()).isEqualTo(2)
                            request.complete(stream)
                            terminal(stream)
                        }
                    })
                    return CompletableFuture.completedFuture(Unit)
                }
            }
            StreamMuxerProtocol.getYamux().createMuxer(multistream,
                protocols.filterNot { it.protocolDescriptor.matchesAny(listOf("/libp2p/connection-selection/1.0.0")) } +
                    object : StrictProtocolBinding<Unit>("/libp2p/connection-selection/1.0.0", responder) {})
        }) }
        protocols { add(Echo()) }
    }
    val child = CompletableFuture<Stream>()
    val processed = CompletableFuture<Unit>()
    a.addStreamVisitor(ChannelVisitor { s ->
        if (transport.first.isDone && s.connection === transport.first.get()) {
            child.complete(s)
            s.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun channelUnregistered(ctx: ChannelHandlerContext) {
                    ctx.fireChannelUnregistered()
                    ctx.executor().execute { processed.complete(Unit) }
                }
            })
        }
    })
    try {
        a.start().get(10, SECONDS); b.start().get(10, SECONDS)
        val selected = a.network.connect(b.peerId, *b.listenAddresses().toTypedArray()).get(10, SECONDS)
        val unused = transport.first.get(10, SECONDS)
        val before = unused.closeFuture().numberOfDependents
        transport.release.complete(Unit)
        CompletableFuture.anyOf(unused.closeFuture(), request).get(10, SECONDS)
        if (request.isDone) {
            // Retirement never becomes a dependency of unrelated/public connects.
            repeat(32) { assertThat(a.network.connect(b.peerId).get(10, SECONDS)).isSameAs(selected) }
            val remote = request.get(10, SECONDS)
            if (mode == "shutdown") {
                assertThat(child.get(10, SECONDS).closeFuture().isDone).isFalse()
                a.stop().get(10, SECONDS)
                remote.closeFuture().get(10, SECONDS)
                child.get(10, SECONDS).closeFuture().get(10, SECONDS)
                processed.get(10, SECONDS)
                assertThat(a.network.connections).isEmpty()
                assertThat(unused.closeFuture().numberOfDependents).isZero()
            } else {
                child.get(10, SECONDS).closeFuture().get(10, SECONDS)
                remote.closeFuture().get(10, SECONDS)
                processed.get(10, SECONDS)
                assertThat(unused.closeFuture().numberOfDependents).isLessThanOrEqualTo(before)
                if (mode != "parentClose") {
                    assertThat(unused.closeFuture().isDone).isFalse()
                    selected.close().get(10, SECONDS)
                    assertThat(a.network.connect(b.peerId).get(10, SECONDS)).isSameAs(unused)
                    val echo = a.newStream<EchoController>(listOf("/test/echo"), unused).controller.get(10, SECONDS)
                    assertThat(echo.echo("$mode recovered").get(10, SECONDS)).isEqualTo("$mode recovered")
                }
            }
            println("event=$mode requests=1 before=$before after=${unused.closeFuture().numberOfDependents}")
        } else {
            unused.closeFuture().get(10, SECONDS)
            assertThat(transport.activeConnections).isEqualTo(1)
            val echo = a.newStream<EchoController>(listOf("/test/echo"), selected).controller.get(10, SECONDS)
            assertThat(echo.echo("develop selected").get(10, SECONDS)).isEqualTo("develop selected")
            println("event=$mode baseline immediate surplus closure")
        }
    } finally {
        transport.start.complete(Unit); transport.release.complete(Unit)
        try { a.stop().get(10, SECONDS) } finally { b.stop().get(10, SECONDS) }
        assertThat(a.network.connections).isEmpty(); assertThat(b.network.connections).isEmpty()
        (a.network.transports + b.network.transports).forEach {
            assertThat(it.activeConnections).isZero(); assertThat(it.activeListeners).isZero()
        }
    }
}

private class LifecycleTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val first = CompletableFuture<Connection>()
    val start = CompletableFuture<Unit>()
    val release = CompletableFuture<Unit>()
    private val count = AtomicInteger()
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> =
        when (count.incrementAndGet()) {
            1 -> start.thenCompose { super.dial(addr, connHandler, preHandler) }.thenCompose { c -> first.complete(c); release.thenApply { c } }
            2 -> { start.complete(Unit); first.thenCompose { super.dial(addr, connHandler, preHandler) } }
            else -> error("Expected two dials")
        }
}
