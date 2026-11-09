package io.libp2p.core

import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.mux.yamux.YamuxFrame
import io.libp2p.protocol.Ping
import io.libp2p.security.tls.TlsSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LargeTlsPingPendingWriteTest {
    @RepeatedTest(10)
    fun `a reply received before write completion leaves the next reply usable`() {
        val size = 200 * 1024
        val heldReplies = AtomicInteger()
        val client = HostBuilder()
            .transport(::TcpTransport)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .build()
        val server = HostBuilder()
            .transport(::TcpTransport)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(Ping(size))
            .listen("/ip4/127.0.0.1/tcp/0")
            .builderModifier { builder ->
                builder.debug.muxFramesHandler.addHandler { connection ->
                    connection.pushHandler(object : ChannelDuplexHandler() {
                        var held: ChannelPromise? = null
                        var transportDone = false
                        var nextRequestRead = false
                        var requests = 0

                        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                            if (msg is YamuxFrame && msg.data?.readableBytes() == size && held == null) {
                                heldReplies.incrementAndGet()
                                held = promise
                                val transport = ctx.newPromise()
                                transport.addListener { result ->
                                    transportDone = true
                                    if (!result.isSuccess) {
                                        promise.tryFailure(result.cause())
                                    } else if (nextRequestRead) {
                                        promise.trySuccess()
                                    }
                                }
                                // Send real bytes now; keep the child write pending until the next request.
                                ctx.write(msg, transport)
                            } else {
                                ctx.write(msg, promise)
                            }
                        }

                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            val next = msg is YamuxFrame && msg.data?.readableBytes() == size && ++requests == 2
                            ctx.fireChannelRead(msg)
                            if (next) {
                                nextRequestRead = true
                                if (transportDone) held?.trySuccess()
                            }
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            held?.tryFailure(ConnectionClosedException())
                            ctx.fireChannelInactive()
                        }
                    })
                }
            }
            .build()
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val connection = client.network.connect(server.peerId, server.listenAddresses().single())
                .get(5, TimeUnit.SECONDS)
            val controller = connection.muxerSession().createStream(Ping(size)).controller.get(5, TimeUnit.SECONDS)
            // Serial calls force the second request to follow receipt of the first real reply.
            repeat(10) {
                assertThatCode {
                    assertThat(controller.ping().get(5, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(0)
                }.doesNotThrowAnyException()
            }
            assertThat(heldReplies.get()).isEqualTo(1)
            assertThat(connection.closeFuture().isDone).isFalse()
        } finally {
            CompletableFuture.allOf(client.stop(), server.stop()).get(5, TimeUnit.SECONDS)
        }
    }
}
