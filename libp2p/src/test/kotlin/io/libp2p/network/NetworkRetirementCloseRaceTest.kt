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
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val CLOSING_RETIREMENT_PROTOCOL = "/libp2p/connection-selection/1.0.0"

class NetworkRetirementCloseRaceTest {
    @Test
    fun `a failed retirement does not revive a parent whose explicit close is pending`() {
        val request = CompletableFuture<Stream>()
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
                                    }
                                })
                                return CompletableFuture.completedFuture(Unit)
                            }
                        }
                        StreamMuxerProtocol.getYamux().createMuxer(
                            multistream,
                            protocols.filterNot { it.protocolDescriptor.matchesAny(listOf(CLOSING_RETIREMENT_PROTOCOL)) } +
                                object : StrictProtocolBinding<Unit>(CLOSING_RETIREMENT_PROTOCOL, responder) {}
                        )
                    }
                )
            }
            protocols { add(Echo()) }
        }
        lateinit var transport: ClosingRetirementTransport
        val a = host {
            transports { add { upgrader -> ClosingRetirementTransport(upgrader).also { transport = it } } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        val terminal = CompletableFuture<Unit>()
        a.addStreamVisitor(
            ChannelVisitor { stream ->
                if (transport.first.isDone && stream.connection === transport.first.get()) {
                    stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                        override fun channelUnregistered(ctx: ChannelHandlerContext) {
                            ctx.fireChannelUnregistered()
                            ctx.executor().execute { terminal.complete(Unit) }
                        }
                    })
                }
            }
        )
        val closeGate = RetirementPhysicalCloseGate()
        try {
            a.start().get(10, TimeUnit.SECONDS)
            b.start().get(10, TimeUnit.SECONDS)
            val selected = a.network.connect(b.peerId, *b.listenAddresses().toTypedArray()).get(10, TimeUnit.SECONDS)
            val unused = transport.first.get(10, TimeUnit.SECONDS)
            transport.releaseFirst.complete(Unit)
            val remoteChild = request.get(10, TimeUnit.SECONDS)
            unused.pushHandler(closeGate)
            val closing = unused.close()
            closeGate.requested.get(10, TimeUnit.SECONDS)
            remoteChild.reset().get(10, TimeUnit.SECONDS)
            terminal.get(10, TimeUnit.SECONDS)
            assertThat(closing.isDone).isFalse()
            assertThat(unused.closeFuture().isDone).isFalse()
            selected.close().get(10, TimeUnit.SECONDS)
            // No address is supplied: reuse must finish exceptionally rather than return this parent.
            val connect = a.network.connect(b.peerId)
            connect.handle { connection, failure ->
                assertThat(connection).isNull()
                assertThat(failure).isNotNull()
            }.get(10, TimeUnit.SECONDS)
            val opening = a.newStream<EchoController>(listOf("/test/echo"), unused)
            opening.controller.handle { controller, failure ->
                assertThat(controller).isNull()
                assertThat(failure).hasMessage("Cannot open a stream because the connection is closed or excluded from selection: $unused")
            }.get(10, TimeUnit.SECONDS)
            closeGate.release.complete(Unit)
            closing.get(10, TimeUnit.SECONDS)
            assertThat(transport.activeConnections).isZero()
        } finally {
            closeGate.release.complete(Unit)
            transport.start.complete(Unit)
            transport.releaseFirst.complete(Unit)
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
}

private class RetirementPhysicalCloseGate : ChannelOutboundHandlerAdapter() {
    val requested = CompletableFuture<Unit>()
    val release = CompletableFuture<Unit>()
    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        requested.complete(Unit)
        release.thenRun { ctx.close(promise) }
    }
}

private class ClosingRetirementTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val first = CompletableFuture<Connection>()
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
                first.thenCompose { super.dial(addr, connHandler, preHandler) }
            }
            else -> error("Expected only two genuine transport dials")
        }
}
