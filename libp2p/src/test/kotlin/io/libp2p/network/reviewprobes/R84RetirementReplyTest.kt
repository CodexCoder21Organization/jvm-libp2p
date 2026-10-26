package io.libp2p.reviewr84

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

class R84RetirementReplyTest {
    @Test
    fun `reset during approval write cannot leave an open parent permanently excluded at acceptor`() {
        lateinit var transport: ReplyDelayedTransport
        val a = host {
            transports { add { ReplyDelayedTransport(it).also { transport = it } } }
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
        val approvalReset = CompletableFuture<Stream>()
        val localTerminal = CompletableFuture<Unit>()
        b.addStreamVisitor(ChannelVisitor { stream ->
            stream.getProtocol().thenAccept { protocol ->
                if (protocol == "/libp2p/connection-selection/1.0.0") {
                    stream.pushHandler(object : ChannelOutboundHandlerAdapter() {
                        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                            if (msg is ByteBuf && msg.readableBytes() == 1 && msg.getUnsignedByte(msg.readerIndex()).toInt() == 1) {
                                // Reset the actual child before its queued approval can be sent.
                                // Forward that same write only after real closure; its ordinary channel write fails.
                                stream.reset().whenComplete { _, _ ->
                                    ctx.executor().execute {
                                        ctx.writeAndFlush(msg, promise)
                                        approvalReset.complete(stream)
                                    }
                                }
                            } else ctx.write(msg, promise)
                        }
                    })
                }
            }
        })
        a.addStreamVisitor(ChannelVisitor { stream ->
            if (transport.first.isDone && stream.connection === transport.first.get()) {
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun channelUnregistered(ctx: ChannelHandlerContext) {
                        ctx.fireChannelUnregistered()
                        ctx.executor().execute { localTerminal.complete(Unit) }
                    }
                })
            }
        })
        try {
            a.start().get(10, SECONDS); b.start().get(10, SECONDS)
            val selected = a.network.connect(b.peerId, *b.listenAddresses().toTypedArray()).get(10, SECONDS)
            val surplus = transport.first.get(10, SECONDS)
            val remote = b.network.connections.single { it.localAddress() == surplus.remoteAddress() && it.remoteAddress() == surplus.localAddress() }
            transport.release.complete(Unit)
            // Develop closes the surplus immediately. The reviewed head opens retirement instead.
            CompletableFuture.anyOf(surplus.closeFuture(), approvalReset).get(10, SECONDS)
            if (!surplus.closeFuture().isDone) {
                approvalReset.get(10, SECONDS)
                localTerminal.get(10, SECONDS)
                assertThat(remote.closeFuture().isDone).isFalse()
                selected.close().get(10, SECONDS)
                assertThat(a.network.connect(b.peerId).get(10, SECONDS)).isSameAs(surplus)
                val forward = a.newStream<EchoController>(listOf("/test/echo"), surplus).controller.get(10, SECONDS)
                assertThat(forward.echo("still live").get(10, SECONDS)).isEqualTo("still live")
                val attempt = b.network.connect(a.peerId).handle { parent, failure ->
                    println("approval reset: remote connect=$parent failure=$failure; remote close=${remote.closeFuture().isDone}")
                    assertThat(failure).describedAs("A live acceptor parent must not stay excluded after its approval reply fails").isNull()
                    assertThat(parent).isSameAs(remote)
                }
                attempt.get(10, SECONDS)
            } else {
                surplus.closeFuture().get(10, SECONDS)
                assertThat(transport.activeConnections).isEqualTo(1)
            }
        } finally {
            transport.start.complete(Unit); transport.release.complete(Unit)
            try { a.stop().get(10, SECONDS) } finally { b.stop().get(10, SECONDS) }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero(); assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private class ReplyDelayedTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
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
