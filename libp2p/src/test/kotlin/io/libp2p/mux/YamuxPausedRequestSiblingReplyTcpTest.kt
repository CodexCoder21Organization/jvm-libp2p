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
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class YamuxPausedRequestSiblingReplyTcpTest {
    @Test
    fun pausedRequestReadsMustNotStopSiblingReplyWindowUpdates() {
        val protocolId = "/test/paused-request-sibling-reply/1.0.0"
        val payload = ByteArray(1_675_062) { (it % 251).toByte() }
        val received = ByteArrayOutputStream()
        val receivedCount = AtomicInteger()
        val replyComplete = CountDownLatch(1)
        val responderPaused = CountDownLatch(1)
        val responders = LinkedBlockingQueue<StreamOverNetty>()
        val receivedOn = AtomicReference<Stream?>()
        val transferFailure = AtomicReference<Throwable?>()
        val binding = object : StrictProtocolBinding<Stream>(
            protocolId,
            object : P2PChannelHandler<Stream> {
                override fun initChannel(ch: P2PChannel): CompletableFuture<Stream> {
                    val stream = ch as Stream
                    stream.pushHandler(object : SimpleChannelInboundHandler<ByteBuf>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            if (stream.isInitiator) {
                                receivedOn.compareAndSet(null, stream)
                                val bytes = ByteArray(msg.readableBytes())
                                msg.readBytes(bytes)
                                received.write(bytes)
                                if (receivedCount.addAndGet(bytes.size) >= payload.size) replyComplete.countDown()
                            } else {
                                // The test sends a request only after both streams have negotiated.
                                ctx.channel().config().isAutoRead = false
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
                    if (!stream.isInitiator) responders.add(stream as StreamOverNetty)
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
            val request = client.newStream<Stream>(listOf(protocolId), server.peerId, server.listenAddresses().single())
                .controller.get(5, TimeUnit.SECONDS)
            val requestResponder = responders.poll(5, TimeUnit.SECONDS)
                ?: throw AssertionError("The server must accept the request stream before it is paused")
            val sibling = client.newStream<Stream>(listOf(protocolId), server.peerId, server.listenAddresses().single())
                .controller.get(5, TimeUnit.SECONDS)
            val siblingResponder = responders.poll(5, TimeUnit.SECONDS)
                ?: throw AssertionError("The server must accept the sibling reply stream before request reads are paused")
            assertSame(requestResponder.nettyChannel.parent(), siblingResponder.nettyChannel.parent(), "Both streams must use the same server connection")
            assertSame((request as StreamOverNetty).nettyChannel.parent(), (sibling as StreamOverNetty).nettyChannel.parent())

            request.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(1))).get(5, TimeUnit.SECONDS)
            assertTrue(responderPaused.await(5, TimeUnit.SECONDS), "The request responder must pause before the sibling reply is sent")
            val write = siblingResponder.writeAndFlushWithFuture(Unpooled.wrappedBuffer(payload))
            assertTrue(
                replyComplete.await(5, TimeUnit.SECONDS) && transferFailure.get() == null,
                "A paused request reader must not block window updates for its sibling's ${payload.size}-byte reply; " +
                    "received ${receivedCount.get()} bytes, write completed=${write.isDone}, failure=${transferFailure.get()}"
            )
            write.get(5, TimeUnit.SECONDS)
            assertNull(transferFailure.get(), "The sibling reply must complete without failure")
            assertSame(sibling, receivedOn.get(), "The reply must arrive on the sibling stream")
            assertArrayEquals(payload, received.toByteArray(), "The sibling reply must arrive completely and in order")
            assertFalse(requestResponder.nettyChannel.config().isAutoRead, "The request stream must remain paused throughout the sibling reply")
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
