package io.libp2p.mux.yamux

import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.SimpleChannelInboundHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class YamuxResumeAtFrameBoundCloseTcpTest {
    @Test
    fun callbackCloseWhileDrainingReleasesTheArrivingFrame() {
        val accepted = CompletableFuture<StreamOverNetty>()
        val arrivingPayload = CompletableFuture<ByteBuf>()
        val delivered = AtomicInteger()
        val firstDelivered = CountDownLatch(1)
        val receivedBytes = ConcurrentLinkedQueue<Int>()
        val allDelivered = CountDownLatch(65)
        val blockedNextFrame = CountDownLatch(1)
        val continueNextFrame = CountDownLatch(1)
        val binding = object : StrictProtocolBinding<Stream>(
            "/test/yamux-resume-bound/1.0.0",
            object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
                override fun onStartInitiator(stream: Stream) = CompletableFuture.completedFuture(stream)
                override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                    val netty = stream as StreamOverNetty
                    netty.nettyChannel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            val count = msg.readableBytes()
                            repeat(count) { receivedBytes.add(msg.readUnsignedByte().toInt()) }
                            delivered.addAndGet(count)
                            if (delivered.get() == 1) {
                                netty.nettyChannel.close()
                                firstDelivered.countDown()
                            }
                            repeat(count) { allDelivered.countDown() }
                        }
                    })
                    accepted.complete(netty)
                    return CompletableFuture.completedFuture(stream)
                }
            }
        ) {}
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add(::PlaintextInsecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(binding) }
        }
        val server = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add(::PlaintextInsecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { add(binding) }
        }
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val sender = client.newStream<Stream>(
                listOf("/test/yamux-resume-bound/1.0.0"),
                server.peerId,
                server.listenAddresses().single()
            ).controller.get(5, TimeUnit.SECONDS)
            val receiver = accepted.get(5, TimeUnit.SECONDS)
            val child = receiver.nettyChannel
            val parent = child.parent()
            child.config().isAutoRead = false
            parent.eventLoop().submit(
                Callable {
                    var frames = 0
                    parent.pipeline().addBefore(
                        parent.pipeline().context(YamuxHandler::class.java).name(),
                        "resume-order-observer",
                        object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                if (msg is YamuxFrame && msg.id == child.id() &&
                                    msg.type == YamuxType.DATA && msg.length > 0 && ++frames == 65
                                ) {
                                    arrivingPayload.complete(msg.data!!)
                                    blockedNextFrame.countDown()
                                    check(continueNextFrame.await(5, TimeUnit.SECONDS)) {
                                        "The next frame must be released after the public resume call"
                                    }
                                }
                                ctx.fireChannelRead(msg)
                            }
                        }
                    )
                }
            ).get(5, TimeUnit.SECONDS)
            repeat(65) { index ->
                sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(index.toByte())))
                    .get(5, TimeUnit.SECONDS)
            }
            assertTrue(blockedNextFrame.await(5, TimeUnit.SECONDS), "The 65th frame must arrive after 64 paused frames")
            assertEquals(0, delivered.get(), "All preceding frames must still be queued")
            child.config().isAutoRead = true
            assertTrue(child.config().isAutoRead, "The public resume flag must be visible before the next frame reaches Yamux")
            continueNextFrame.countDown()
            assertTrue(firstDelivered.await(5, TimeUnit.SECONDS), "Resume must begin draining the held FIFO")
            child.closeFuture().get(5, TimeUnit.SECONDS)
            parent.eventLoop().submit(Callable { Unit }).get(5, TimeUnit.SECONDS)
            assertEquals(1, delivered.get(), "A callback close must stop delivery and release the arriving frame")
            assertEquals(listOf(0), receivedBytes.toList())
            assertEquals(0, arrivingPayload.get().refCnt(), "The arriving payload must be released after callback close")
            assertTrue(parent.isOpen, "Closing the resumed child must leave the shared connection open")
            assertTrue(parent.config().isAutoRead, "Closing the resumed child must leave control traffic readable")
        } finally {
            continueNextFrame.countDown()
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
