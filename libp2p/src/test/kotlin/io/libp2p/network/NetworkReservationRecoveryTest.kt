package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.StreamPromise
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerDebug
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NetworkReservationRecoveryTest {
    @ParameterizedTest
    @ValueSource(strings = ["yamux", "mplex"])
    fun `refused reservations do no wire work and leave recovery and own stream creation usable`(muxer: String) {
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
        val opens = AtomicInteger()
        val frames = AtomicInteger()
        val a = recoveryCountedHost(muxer, opens, frames)
        val b = recoveryCountedHost(muxer, opens, frames)
        val accepted = CompletableFuture<Connection>()
        b.addConnectionHandler(ConnectionHandler.create { accepted.complete(it) })
        val approvals = LinkedBlockingQueue<() -> CompletableFuture<Unit>>()
        b.addStreamVisitor(ChannelVisitor { stream ->
            stream.getProtocol().thenAccept { protocol ->
                if (protocol == "/libp2p/connection-selection/1.0.0") {
                    stream.pushHandler(object : ChannelOutboundHandlerAdapter() {
                        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                            if (msg is ByteBuf && msg.readableBytes() == 1 && msg.getUnsignedByte(msg.readerIndex()).toInt() == 1) {
                                approvals.add {
                                    val failed = CompletableFuture<Unit>()
                                    stream.reset().whenComplete { _, failure ->
                                        if (failure != null) failed.completeExceptionally(failure)
                                        ctx.executor().execute {
                                            ctx.writeAndFlush(msg, promise)
                                            promise.addListener { result ->
                                                if (result.isSuccess) {
                                                    failed.completeExceptionally(AssertionError("Approval write on reset child must fail"))
                                                } else {
                                                    ctx.executor().execute { failed.complete(Unit) }
                                                }
                                            }
                                        }
                                    }
                                    failed
                                }
                            } else {
                                ctx.write(msg, promise)
                            }
                        }
                    })
                }
            }
        })
        var activeRelease: (() -> CompletableFuture<Unit>)? = null
        try {
            a.start().get(10, SECONDS)
            b.start().get(10, SECONDS)
            val parent = a.network.connect(b.peerId, b.listenAddresses().single()).get(10, SECONDS)
            val remote = accepted.get(10, SECONDS)
            // Each failed write restores the same parent; the next approval proves refusal did not retain it.
            repeat(2) {
                val request = parent.muxerSession().createStream(listOf(retirement))
                val release = approvals.poll(10, SECONDS)
                activeRelease = release
                assertThat(release).describedAs("An unretained parent must approve retirement again after recovery").isNotNull()
                recoveryWireBarrier(parent)
                recoveryWireBarrier(remote)
                val before = listOf(opens.get(), frames.get())
                repeat(32) {
                    assertThat(b.network.reserveConnection(remote).get(10, SECONDS)).isFalse()
                }
                recoveryWireBarrier(parent)
                recoveryWireBarrier(remote)
                assertThat(listOf(opens.get(), frames.get()))
                    .describedAs("Refused reservations must open no streams and write no frames").isEqualTo(before)
                activeRelease = null
                release!!.invoke().get(10, SECONDS)
                assertThat(request.controller.handle { _, failure -> failure }.get(10, SECONDS)).isNotNull()
                assertThat(remote.closeFuture().isDone).isFalse()
            }
            val beforeEcho = listOf(opens.get(), frames.get())
            val echo = b.newStream<EchoController>(listOf("/test/echo"), remote).controller.get(10, SECONDS)
            assertThat(echo.echo("recovered reservation").get(10, SECONDS)).isEqualTo("recovered reservation")
            recoveryWireBarrier(parent)
            recoveryWireBarrier(remote)
            assertThat(opens.get()).isGreaterThan(beforeEcho[0])
            assertThat(frames.get()).isGreaterThan(beforeEcho[1])
            val before = listOf(opens.get(), frames.get())
            repeat(32) { assertThat(b.network.reserveConnection(remote).get(10, SECONDS)).isTrue() }
            recoveryWireBarrier(parent)
            recoveryWireBarrier(remote)
            assertThat(listOf(opens.get(), frames.get()))
                .describedAs("Repeated successful reservations must open no streams and write no frames").isEqualTo(before)
            assertThat(parent.muxerSession().createStream(listOf(retirement)).controller.get(10, SECONDS)).isFalse()
            assertThat(echo.echo("retained own stream").get(10, SECONDS)).isEqualTo("retained own stream")
        } finally {
            activeRelease?.invoke()?.get(10, SECONDS)
            while (approvals.isNotEmpty()) approvals.poll()?.invoke()?.get(10, SECONDS)
            try { a.stop().get(10, SECONDS) } finally { b.stop().get(10, SECONDS) }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private fun recoveryCountedHost(muxer: String, opens: AtomicInteger, frames: AtomicInteger): Host = host {
    network { listen("/ip4/127.0.0.1/tcp/0") }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers {
        add(
            StreamMuxerProtocol { multistream, protocols ->
                val real = (if (muxer == "yamux") StreamMuxerProtocol.getYamux() else StreamMuxerProtocol.Mplex)
                    .createMuxer(multistream, protocols)
                (real as StreamMuxerDebug).muxFramesDebugHandler = ChannelVisitor { connection ->
                    connection.pushHandler(object : ChannelOutboundHandlerAdapter() {
                        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                            frames.incrementAndGet()
                            ctx.write(msg, promise)
                        }
                    })
                }
                object : StreamMuxer by real {
                    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> =
                        real.initChannel(ch, selectedProtocol).thenApply { session ->
                            object : StreamMuxer.Session by session {
                                override fun <T> createStream(protocol: ProtocolBinding<T>): StreamPromise<T> =
                                    createStream(listOf(protocol))

                                override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
                                    opens.incrementAndGet()
                                    return session.createStream(protocols)
                                }
                            }
                        }
                }
            }
        )
    }
    protocols { add(Echo()) }
}

private fun recoveryWireBarrier(parent: Connection) {
    (parent as P2PChannelOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS)
}
