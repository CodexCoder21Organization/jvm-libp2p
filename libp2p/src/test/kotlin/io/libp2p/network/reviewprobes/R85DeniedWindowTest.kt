package io.libp2p.reviewr85

import io.libp2p.core.*
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

/**
 * Question (a): A's connection handler holds the surplus parent before settlement. B has opened
 * an Echo stream on that parent (so B retains it and will deny retirement). While A's retirement
 * request is pending (B's 0 reply is held), A opens a stream on the handler-held parent. Then
 * B's denial is released and the parent is kept.
 */
class R85DeniedWindowTest {
    @Test
    fun `stream open on handler-held duplicate during a pending request that the peer denies`() {
        lateinit var transport: R85HeldTransport
        val a = host {
            transports { add { R85HeldTransport(it).also { transport = it } } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        val b = host {
            network { listen("/ip4/127.0.0.1/tcp/0"); listen("/ip4/127.0.0.2/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        val handlerHeld = CompletableFuture<Connection>()
        a.addConnectionHandler(ConnectionHandler { c -> if (!handlerHeld.isDone) handlerHeld.complete(c) })
        val requestSeen = CompletableFuture<Unit>()
        val releaseReply = CompletableFuture<Unit>()
        val replyByte = CompletableFuture<Int>()
        b.addStreamVisitor(ChannelVisitor { stream ->
            stream.getProtocol().thenAccept { protocol ->
                if (protocol == "/libp2p/connection-selection/1.0.0") {
                    requestSeen.complete(Unit)
                    stream.pushHandler(object : ChannelOutboundHandlerAdapter() {
                        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                            if (msg is ByteBuf && msg.readableBytes() == 1) {
                                replyByte.complete(msg.getUnsignedByte(msg.readerIndex()).toInt())
                                releaseReply.whenComplete { _, _ -> ctx.executor().execute { ctx.writeAndFlush(msg, promise) } }
                            } else ctx.write(msg, promise)
                        }
                    })
                }
            }
        })
        try {
            a.start().get(10, SECONDS); b.start().get(10, SECONDS)
            val connectFuture = a.network.connect(b.peerId, *b.listenAddresses().toTypedArray())
            val surplus = transport.first.get(10, SECONDS)
            assertThat(handlerHeld.get(10, SECONDS)).isSameAs(surplus)
            val selected = connectFuture.get(10, SECONDS)
            assertThat(selected).isNotSameAs(surplus)
            val remote = b.network.connections.single { it.localAddress() == surplus.remoteAddress() && it.remoteAddress() == surplus.localAddress() }
            // B publishes its end of the surplus parent by opening a stream on it.
            val bEcho = b.newStream<EchoController>(listOf("/test/echo"), remote).controller.get(10, SECONDS)
            assertThat(bEcho.echo("b-before").get(10, SECONDS)).isEqualTo("b-before")
            transport.release.complete(Unit)
            CompletableFuture.anyOf(surplus.closeFuture(), requestSeen).get(10, SECONDS)
            if (!requestSeen.isDone) {
                println("R85-A develop-path: surplus closed by duplicate decision while B had an open stream on it; remoteClosed=${remote.closeFuture().isDone}")
                remote.closeFuture().get(10, SECONDS)
                assertThat(remote.closeFuture().isDone).describedAs("B-published parent closed").isFalse()
                return
            }
            // Request pending at A (B's reply is held). Open a stream on the handler-held parent.
            val inWindow = a.newStream<EchoController>(listOf("/test/echo"), surplus).controller
                .handle { c, f -> Pair(c, f) }.get(10, SECONDS)
            println("R85-A window stream: controller=${inWindow.first} failure=${inWindow.second}")
            println("R85-A reply byte held at B = ${replyByte.get(10, SECONDS)}")
            releaseReply.complete(Unit)
            // Allow the reply to be processed: an Echo round trip on the selected parent orders after it on the event loop is not guaranteed,
            // so wait on a stream open that succeeds once eligibility is restored, bounded.
            var after: Pair<EchoController?, Throwable?> = Pair(null, null)
            val deadline = System.nanoTime() + SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                after = a.newStream<EchoController>(listOf("/test/echo"), surplus).controller.handle { c, f -> Pair(c, f) }.get(10, SECONDS)
                if (after.second == null || surplus.closeFuture().isDone) break
                Thread.yield()
            }
            println("R85-A after denial: surplusClosed=${surplus.closeFuture().isDone} remoteClosed=${remote.closeFuture().isDone} selectedClosed=${selected.closeFuture().isDone} afterFailure=${after.second}")
            assertThat(surplus.closeFuture().isDone).isFalse()
            assertThat(remote.closeFuture().isDone).isFalse()
            assertThat(selected.closeFuture().isDone).isFalse()
            assertThat(after.second).isNull()
            assertThat(after.first!!.echo("a-after").get(10, SECONDS)).isEqualTo("a-after")
            assertThat(bEcho.echo("b-after").get(10, SECONDS)).isEqualTo("b-after")
            assertThat(a.network.connect(b.peerId).get(10, SECONDS)).isSameAs(selected)
            assertThat(inWindow.second)
                .describedAs("stream open during the pending window on a parent that is then kept")
                .isNull()
        } finally {
            transport.start.complete(Unit); transport.release.complete(Unit); releaseReply.complete(Unit)
            try { a.stop().get(10, SECONDS) } finally { b.stop().get(10, SECONDS) }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero(); assertThat(it.activeListeners).isZero()
            }
        }
    }
}

internal class R85HeldTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
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
