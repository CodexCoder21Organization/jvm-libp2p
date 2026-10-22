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

private const val smallFrameCount = 10_000

// The paused-object bound Mplex applies; Yamux must stay within it by coalescing, not by resetting.
private const val maxRetainedPayloadObjects = 64

/**
 * A peer that stays inside the Yamux receive window may send its bytes in any frame size. A paused
 * receiver must keep the stream open and deliver every byte in order after resuming, while holding
 * a bounded number of payload objects. Each queued object is delivered as one read, so the number
 * of reads after resume is the number of objects the paused stream retained.
 */
class YamuxPausedManySmallFramesTcpTest {
    @Test
    fun pausedStreamCoalescesManyOneByteFramesWithinItsWindow() {
        val accepted = CompletableFuture<StreamOverNetty>()
        val deliveredBytes = AtomicInteger()
        val deliveredReads = AtomicInteger()
        val receivedBytes = ConcurrentLinkedQueue<Int>()
        val allDelivered = CountDownLatch(smallFrameCount)
        val allFramesReachedYamux = CountDownLatch(smallFrameCount)
        val trailingDelivered = CountDownLatch(smallFrameCount + 1)
        val binding = object : StrictProtocolBinding<Stream>(
            "/test/yamux-paused-small-frames/1.0.0",
            object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
                override fun onStartInitiator(stream: Stream) = CompletableFuture.completedFuture(stream)
                override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                    val netty = stream as StreamOverNetty
                    netty.nettyChannel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            val count = msg.readableBytes()
                            repeat(count) { receivedBytes.add(msg.readUnsignedByte().toInt()) }
                            deliveredReads.incrementAndGet()
                            deliveredBytes.addAndGet(count)
                            repeat(count) {
                                allDelivered.countDown()
                                trailingDelivered.countDown()
                            }
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
                listOf("/test/yamux-paused-small-frames/1.0.0"),
                server.peerId,
                server.listenAddresses().single()
            ).controller.get(5, TimeUnit.SECONDS)
            val receiver = accepted.get(5, TimeUnit.SECONDS)
            val child = receiver.nettyChannel
            val parent = child.parent()
            child.config().isAutoRead = false
            parent.eventLoop().submit(
                Callable {
                    parent.pipeline().addBefore(
                        parent.pipeline().context(YamuxHandler::class.java).name(),
                        "small-frame-counter",
                        object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                val counted = msg is YamuxFrame && msg.id == child.id() &&
                                    msg.type == YamuxType.DATA && msg.length == 1L
                                ctx.fireChannelRead(msg)
                                // Counted after Yamux has processed the frame.
                                if (counted) allFramesReachedYamux.countDown()
                            }
                        }
                    )
                }
            ).get(5, TimeUnit.SECONDS)

            val writes = List(smallFrameCount) { index ->
                sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(index.toByte())))
            }
            CompletableFuture.allOf(*writes.toTypedArray()).get(30, TimeUnit.SECONDS)
            assertTrue(
                allFramesReachedYamux.await(30, TimeUnit.SECONDS),
                "All $smallFrameCount one-byte DATA frames must reach Yamux while the stream is paused; " +
                    "remaining=${allFramesReachedYamux.count} childOpen=${child.isOpen}"
            )
            parent.eventLoop().submit(Callable { Unit }).get(5, TimeUnit.SECONDS)
            assertEquals(0, deliveredBytes.get(), "A paused stream must not deliver its held bytes")
            assertTrue(
                child.isOpen,
                "$smallFrameCount one-byte frames fit the receive window, so the paused stream must stay open"
            )

            child.config().isAutoRead = true
            assertTrue(
                allDelivered.await(30, TimeUnit.SECONDS),
                "Resume must deliver all $smallFrameCount held bytes; actual=${deliveredBytes.get()} childOpen=${child.isOpen}"
            )
            parent.eventLoop().submit(Callable { Unit }).get(5, TimeUnit.SECONDS)
            assertEquals(smallFrameCount, deliveredBytes.get())
            assertEquals(
                List(smallFrameCount) { it and 0xff },
                receivedBytes.toList(),
                "Held bytes must be delivered in FIFO order"
            )
            val readsAfterResume = deliveredReads.get()
            assertTrue(
                readsAfterResume <= maxRetainedPayloadObjects,
                "A paused stream holding $smallFrameCount one-byte frames retained $readsAfterResume payload " +
                    "objects; it must retain at most $maxRetainedPayloadObjects"
            )
            assertTrue(child.isOpen, "A resumed stream must remain open")

            sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(0x5a))).get(5, TimeUnit.SECONDS)
            assertTrue(
                trailingDelivered.await(5, TimeUnit.SECONDS),
                "The resumed stream must keep delivering new data; actual=${deliveredBytes.get()}"
            )
            assertEquals(0x5a, receivedBytes.last())
            assertTrue(child.isOpen, "The stream must stay open after further data")
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
