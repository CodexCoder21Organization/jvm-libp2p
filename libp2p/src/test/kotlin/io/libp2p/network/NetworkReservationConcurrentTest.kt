package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

class NetworkReservationConcurrentTest {
    @Test
    fun `concurrent reservations and retirement never both succeed`() {
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
            network { listen("/ip4/127.0.0.1/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        val accepted = LinkedBlockingQueue<Connection>()
        b.addConnectionHandler(ConnectionHandler.create { accepted.add(it) })
        val releases = CopyOnWriteArrayList<CompletableFuture<Unit>>()
        val readyRetirements = LinkedBlockingQueue<CompletableFuture<Unit>>()
        b.addStreamVisitor(
            ChannelVisitor { stream ->
                stream.getProtocol().thenAccept { protocol ->
                    if (protocol == "/libp2p/connection-selection/1.0.0") {
                        stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                if (msg is ByteBuf && msg.readableBytes() == 1 && msg.getUnsignedByte(msg.readerIndex()).toInt() == 2) {
                                    val start = CompletableFuture<Unit>()
                                    releases.add(start)
                                    readyRetirements.add(start)
                                    // Defer the genuine request without blocking its event loop.
                                    start.thenRun { ctx.executor().execute { ctx.fireChannelRead(msg) } }
                                } else {
                                    ctx.fireChannelRead(msg)
                                }
                            }
                        })
                    }
                }
            }
        )
        val callers = Executors.newFixedThreadPool(32)
        try {
            a.start().get(10, SECONDS)
            b.start().get(10, SECONDS)
            // Each iteration needs a fresh physical parent because successful retention lasts for its lifetime.
            repeat(64) { iteration ->
                val parent = a.network.connect(b.peerId, b.listenAddresses().single()).get(10, SECONDS)
                val remote = checkNotNull(accepted.poll(10, SECONDS)) { "Connection callback did not publish the parent" }
                assertThat(remote).isNotNull()
                val request = parent.muxerSession().createStream(listOf(retirement))
                val start = checkNotNull(readyRetirements.poll(10, SECONDS)) { "Retirement request did not reach its public inbound gate" }
                assertThat(start).isNotNull()
                val ready = CountDownLatch(32)
                val results = (0 until 32).map {
                    CompletableFuture.supplyAsync({
                        ready.countDown()
                        start.get(10, SECONDS)
                        b.network.reserveConnection(remote).get(10, SECONDS)
                    }, callers)
                }
                assertThat(ready.await(10, SECONDS)).isTrue()
                start.complete(Unit)
                val approved = request.controller.get(10, SECONDS)
                val reserved = results.map { it.get(10, SECONDS) }
                assertThat(approved && reserved.any { it })
                    .describedAs("Reservation and retirement cannot both succeed on parent %s", iteration).isFalse()
                if (reserved.any { it }) {
                    assertThat(reserved).containsOnly(true)
                    val echo = b.newStream<EchoController>(listOf("/test/echo"), remote).controller.get(10, SECONDS)
                    assertThat(echo.echo("concurrent reservation $iteration").get(10, SECONDS))
                        .isEqualTo("concurrent reservation $iteration")
                } else {
                    assertThat(approved).isTrue()
                    assertThat(b.network.reserveConnection(remote).get(10, SECONDS)).isFalse()
                }
                parent.close().get(10, SECONDS)
                remote.closeFuture().get(10, SECONDS)
            }
        } finally {
            releases.forEach { it.complete(Unit) }
            try {
                callers.shutdownNow()
                assertThat(callers.awaitTermination(10, SECONDS)).isTrue()
            } finally {
                try {
                    a.stop().get(10, SECONDS)
                } finally {
                    b.stop().get(10, SECONDS)
                }
            }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}
