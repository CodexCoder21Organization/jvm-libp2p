package io.libp2p.mux

import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class YamuxPausedRequestLargeReplyTcpTest {
    @Test
    fun pausedRequestReadsMustNotStopLargeReplyWindowUpdates() {
        val protocolId = "/test/paused-request-large-reply/1.0.0"
        val payload = ByteArray(1_675_062) { (it % 251).toByte() }
        val received = ByteArrayOutputStream()
        val receivedCount = AtomicInteger()
        val replyComplete = CountDownLatch(1)
        val responderPaused = CountDownLatch(1)
        val responseWriteComplete = CountDownLatch(1)
        val transferFailure = AtomicReference<Throwable?>()
        val binding = object : StrictProtocolBinding<Stream>(
            protocolId,
            object : P2PChannelHandler<Stream> {
                override fun initChannel(ch: P2PChannel): CompletableFuture<Stream> {
                    val stream = ch as Stream
                    stream.pushHandler(object : SimpleChannelInboundHandler<ByteBuf>() {
                        private var requestReceived = false

                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            if (stream.isInitiator) {
                                val bytes = ByteArray(msg.readableBytes())
                                msg.readBytes(bytes)
                                received.write(bytes)
                                if (receivedCount.addAndGet(bytes.size) >= payload.size) replyComplete.countDown()
                            } else if (!requestReceived) {
                                requestReceived = true
                                // Request handling pauses further requests until its reply has been written.
                                ctx.channel().config().isAutoRead = false
                                stream.writeAndFlushWithFuture(Unpooled.wrappedBuffer(payload)).whenComplete { _, failure ->
                                    if (failure != null) transferFailure.compareAndSet(null, failure)
                                    ctx.channel().config().isAutoRead = true
                                    responseWriteComplete.countDown()
                                }
                                responderPaused.countDown()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            transferFailure.compareAndSet(null, cause)
                            replyComplete.countDown()
                            ctx.fireExceptionCaught(cause)
                        }
                    })
                    return CompletableFuture.completedFuture(stream)
                }
            }
        ) {}
        val createHost = {
            host {
                identity { factory = { generateKeyPair(KeyType.ED25519).first } }
                transports { +::TcpTransport }
                secureChannels { add(::NoiseXXSecureChannel) }
                muxers { +StreamMuxerProtocol.getYamux() }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                protocols { +binding }
            }
        }
        val server = createHost()
        val client = createHost()
        var primary: Throwable? = null
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val stream = client.newStream<Stream>(listOf(protocolId), server.peerId, server.listenAddresses().first())
                .controller.get(5, TimeUnit.SECONDS)
            stream.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(1))).get(5, TimeUnit.SECONDS)
            assertTrue(responderPaused.await(5, TimeUnit.SECONDS), "The responder must receive the request and pause further requests")
            assertTrue(
                replyComplete.await(5, TimeUnit.SECONDS) && transferFailure.get() == null,
                "A paused request reader must still receive Yamux window updates so its ${payload.size}-byte reply completes; " +
                    "received ${receivedCount.get()} bytes, write completed=${responseWriteComplete.count == 0L}, failure=${transferFailure.get()}"
            )
            assertArrayEquals(payload, received.toByteArray(), "The complete large reply must arrive in order")
            assertTrue(responseWriteComplete.await(5, TimeUnit.SECONDS), "The reply write must report completion")
            assertNull(transferFailure.get(), "The reply write and receive must complete without failure")
        } catch (failure: Throwable) {
            primary = failure
        } finally {
            for (node in listOf(client, server)) {
                try {
                    node.stop().get(5, TimeUnit.SECONDS)
                } catch (failure: Throwable) {
                    val earlier = primary
                    if (earlier == null) primary = failure else earlier.addSuppressed(failure)
                }
            }
        }
        primary?.let { throw it }
    }
}
