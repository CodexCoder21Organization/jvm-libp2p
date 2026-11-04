package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
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
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS

class NetworkReservationDecoratedConnectTest {
    @Test
    fun `library connect retains a decorated parent before stream creation`() {
        val retirement = object : StrictProtocolBinding<Boolean>(
            "/libp2p/connection-selection/1.0.0",
            object : ProtocolHandler<Boolean>(1, 1) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Boolean> {
                    val result = CompletableFuture<Boolean>()
                    stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
                        override fun onActivated(stream: Stream) {
                            stream.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(2)).whenComplete { _, failure ->
                                if (failure != null) result.completeExceptionally(failure)
                            }
                        }
                        override fun onMessage(stream: Stream, msg: ByteBuf) {
                            assertThat(msg.readableBytes()).isEqualTo(1)
                            val reply = msg.readUnsignedByte().toInt()
                            assertThat(reply).isIn(0, 1)
                            result.complete(reply == 1)
                        }
                        override fun onClosed(stream: Stream) {
                            result.completeExceptionally(ConnectionClosedException("Retirement stream closed before its reply"))
                        }
                        override fun onException(cause: Throwable?) {
                            result.completeExceptionally(cause ?: ConnectionClosedException("Retirement stream failed before its reply"))
                        }
                    })
                    return result
                }
            }
        ) {}
        val a = host {
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        val b = host {
            transports { add(::DecoratedConnectTransport) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        val accepted = CompletableFuture<Connection>()
        b.addConnectionHandler(ConnectionHandler.create { accepted.complete(it) })
        try {
            a.start().get(10, SECONDS)
            b.start().get(10, SECONDS)
            val parent = a.network.connect(b.peerId, b.listenAddresses().single()).get(10, SECONDS)
            // The peer's publication is independent of local dial completion.
            val remote = accepted.get(10, SECONDS)
            assertThat(b.network.connections).contains(remote)
            assertThat(b.network.connect(a.peerId).get(10, SECONDS)).isSameAs(remote)
            assertThat(b.network.connect(a.peerId).get(10, SECONDS)).isSameAs(remote)
            val request = parent.muxerSession().createStream(listOf(retirement))
            assertThat(request.controller.get(10, SECONDS))
                .describedAs("Library connect must retain the same session forwarded by a decorated parent")
                .isFalse()
            val echo = b.newStream<EchoController>(listOf("/test/echo"), remote).controller.get(10, SECONDS)
            assertThat(echo.echo("reserved parent").get(10, SECONDS)).isEqualTo("reserved parent")
            remote.close().get(10, SECONDS)
            assertThat(b.network.reserveConnection(remote).get(10, SECONDS)).isFalse()
            assertThat(b.network.reserveConnection(remote).get(10, SECONDS)).isFalse()
        } finally {
            try {
                a.stop().get(10, SECONDS)
            } finally {
                b.stop().get(10, SECONDS)
            }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private class DecoratedConnectTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    override fun listen(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Unit> = super.listen(
        addr,
        ConnectionHandler.create { parent -> connHandler.handleConnection(object : Connection by parent {}) },
        preHandler
    )
}
