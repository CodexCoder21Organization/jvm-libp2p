@file:Suppress("DEPRECATION")

package io.libp2p.mux.yamux

import io.libp2p.core.PeerId
import io.libp2p.core.StreamHandler
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.WRITE_FAILURE
import io.libp2p.mux.YamuxOutboundBufferExceededException
import io.libp2p.tools.NullTransport
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.ReferenceCountUtil
import io.netty.util.ResourceLeakDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

class YamuxOutboundBufferBudgetRefCountTest {

    @Test
    fun `inbound data is released when window update budget rejection closes connection`() {
        withParanoidLeakDetection {
            YamuxHarness(maxBufferedConnectionWrites = 1024).use { harness ->
                val dataRefCnt = harness.onClientEventLoop {
                    val streamId = YamuxId(harness.client.id(), YamuxStreamIdGenerator(false).next())
                    harness.handler.channelRead(
                        harness.handler.getChannelHandlerContext(),
                        YamuxFrame(streamId, YamuxType.DATA, YamuxFlag.SYN.asSet, 0)
                    )

                    harness.client.write(harness.client.alloc().buffer(2048).writeZero(2048))

                    val length = (INITIAL_WINDOW_SIZE / 2) + 1
                    val data = harness.client.alloc().buffer(length).writeZero(length)
                    val failure = catchBudgetFailure {
                        harness.handler.channelRead(
                            harness.handler.getChannelHandlerContext(),
                            YamuxFrame(streamId, YamuxType.DATA, YamuxFlag.NONE, length.toLong(), data)
                        )
                    }
                    assertThat(failure.message).startsWith("Yamux parent outbound buffer exceeded configured budget;")
                    data.refCnt()
                }

                assertThat(dataRefCnt).isZero()
            }
        }
    }

    @Test
    fun `budget rejection releases retained outbound slices that were not written`() {
        withParanoidLeakDetection {
            YamuxHarness(
                maxBufferedConnectionWrites = 600,
                maxFrameDataLength = 256,
                initialWindowSize = 700,
                suppressFlushes = true
            ).use { harness ->
                val stream = harness.handler.createStream(
                    StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) }
                ).stream.get(5, TimeUnit.SECONDS)

                harness.onClientEventLoop {
                    harness.client.write(harness.client.alloc().buffer(300).writeZero(300))
                }
                val data = harness.client.alloc().buffer(700).writeZero(700)
                val failure = try {
                    stream.writeAndFlushWithFuture(data).join()
                    throw AssertionError("Expected multi-slice Yamux write to fail on the parent outbound budget")
                } catch (e: CompletionException) {
                    e.cause ?: e
                }

                assertThat(failure).isInstanceOf(YamuxOutboundBufferExceededException::class.java)
                harness.awaitClientClose()
                assertThat(data.refCnt()).isZero()
            }
        }
    }

    @Test
    fun `budget close fails undelivered in-flight parent write futures`() {
        withParanoidLeakDetection {
            YamuxHarness(
                maxBufferedConnectionWrites = 500,
                maxFrameDataLength = 1024,
                initialWindowSize = 1024,
                holdOutboundDataFrames = true
            ).use { harness ->
                val stream = harness.handler.createStream(
                    StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) }
                ).stream.get(5, TimeUnit.SECONDS)

                harness.onClientEventLoop {
                    harness.client.write(harness.client.alloc().buffer(300).writeZero(300))
                }

                val firstData = harness.client.alloc().buffer(100).writeZero(100)
                val inFlightFuture = stream.writeAndFlushWithFuture(firstData)
                assertThat(inFlightFuture.isDone).isFalse()

                val secondData = harness.client.alloc().buffer(300).writeZero(300)
                val budgetFailure = try {
                    stream.writeAndFlushWithFuture(secondData).join()
                    throw AssertionError("Expected second Yamux write to fail on the parent outbound budget")
                } catch (e: CompletionException) {
                    e.cause ?: e
                }
                assertThat(budgetFailure).isInstanceOf(YamuxOutboundBufferExceededException::class.java)

                val inFlightFailure = awaitFailure(inFlightFuture)
                assertThat(inFlightFailure).isSameAs(budgetFailure)
                assertThat(firstData.refCnt()).isZero()
                assertThat(secondData.refCnt()).isZero()
            }
        }
    }

    private fun withParanoidLeakDetection(block: () -> Unit) {
        val previousLevel = ResourceLeakDetector.getLevel()
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
        try {
            block()
        } finally {
            ResourceLeakDetector.setLevel(previousLevel)
        }
    }

    private fun catchBudgetFailure(block: () -> Unit): YamuxOutboundBufferExceededException {
        return try {
            block()
            throw AssertionError("Expected Yamux parent outbound-buffer budget failure")
        } catch (e: YamuxOutboundBufferExceededException) {
            e
        }
    }

    private fun awaitFailure(future: CompletableFuture<Unit>): Throwable {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (future.isCompletedExceptionally) {
                return try {
                    future.join()
                    throw AssertionError("Expected completed exceptional future to throw")
                } catch (e: CompletionException) {
                    e.cause ?: e
                }
            }
            Thread.sleep(10)
        }
        throw AssertionError("Expected in-flight Yamux write future to fail after budget-triggered close")
    }

    private class YamuxHarness(
        maxBufferedConnectionWrites: Int,
        maxFrameDataLength: Int = 1024 * 1024,
        initialWindowSize: Int = INITIAL_WINDOW_SIZE,
        private val holdOutboundDataFrames: Boolean = false,
        private val suppressFlushes: Boolean = false
    ) : AutoCloseable {
        private val group: EventLoopGroup = NioEventLoopGroup(1)
        private val localPeerId = PeerId.random()
        private val remoteKey = generateKeyPair(KeyType.RSA).second
        private val remotePeerId = PeerId.fromPubKey(remoteKey)
        val handler = YamuxHandler(
            MultistreamProtocolV1,
            maxFrameDataLength,
            null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true,
            maxBufferedConnectionWrites,
            DEFAULT_ACK_BACKLOG_LIMIT,
            initialWindowSize
        )
        val server: Channel
        val client: Channel

        init {
            server = ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            ch.config().isAutoRead = false
                        }
                    }
                )
                .bind(InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel()

            client = Bootstrap()
                .group(group)
                .channel(NioSocketChannel::class.java)
                .handler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            val connection = ConnectionOverNetty(ch, NullTransport(), true)
                            connection.setSecureSession(
                                SecureChannel.Session(localPeerId, remotePeerId, remoteKey, null)
                            )
                            ch.pipeline().addLast(YamuxFrameCodec(maxFrameDataLength))
                            if (holdOutboundDataFrames || suppressFlushes) {
                                ch.pipeline().addLast(
                                    BudgetTestOutboundHandler(
                                        holdOutboundDataFrames = holdOutboundDataFrames,
                                        suppressFlushes = suppressFlushes
                                    )
                                )
                            }
                            ch.pipeline().addLast(handler)
                        }
                    }
                )
                .connect(server.localAddress())
                .sync()
                .channel()
        }

        fun <T> onClientEventLoop(block: () -> T): T =
            client.eventLoop().submit<T> { block() }.get(5, TimeUnit.SECONDS)

        fun awaitClientClose() {
            assertThat(client.closeFuture().await(5, TimeUnit.SECONDS)).isTrue()
        }

        override fun close() {
            closeChannel(client)
            closeChannel(server)
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS)
        }

        private fun closeChannel(channel: Channel) {
            channel.close().await(5, TimeUnit.SECONDS)
        }
    }

    private class BudgetTestOutboundHandler(
        private val holdOutboundDataFrames: Boolean,
        private val suppressFlushes: Boolean
    ) : ChannelOutboundHandlerAdapter() {
        private val heldWrites = mutableListOf<Pair<Any, ChannelPromise>>()

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (
                holdOutboundDataFrames &&
                msg is YamuxFrame &&
                msg.type == YamuxType.DATA &&
                msg.data?.isReadable == true
            ) {
                heldWrites += msg to promise
            } else {
                ctx.write(msg, promise)
            }
        }

        override fun flush(ctx: ChannelHandlerContext) {
            if (!suppressFlushes) {
                ctx.flush()
            }
        }

        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
            val cause = ctx.channel().attr(WRITE_FAILURE).get() ?: ClosedChannelException()
            heldWrites.forEach { (msg, heldPromise) ->
                ReferenceCountUtil.release(msg)
                heldPromise.tryFailure(cause)
            }
            heldWrites.clear()
            ctx.close(promise)
        }
    }
}
