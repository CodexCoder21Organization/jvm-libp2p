package io.libp2p.mux

import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.util.netty.mux.AbstractMuxHandler
import io.libp2p.mux.mplex.MplexFrame
import io.libp2p.mux.yamux.YamuxFrame
import io.libp2p.mux.yamux.YamuxType
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.SimpleChannelInboundHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PausedChildConcurrentReadTcpTest {
    @ParameterizedTest
    @ValueSource(strings = ["mplex", "yamux"])
    fun concurrentRequestsBeforeDataCoalesceAndKeepAutomaticReadsPaused(muxer: String) {
        val accepted = CompletableFuture<StreamOverNetty>()
        val forwarded = LinkedBlockingQueue<Int>()
        val events = mutableListOf<String>()
        val binding = object : StrictProtocolBinding<Stream>(
            "/test/paused-child-manual-read/1.0.0",
            object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> =
                    CompletableFuture.completedFuture(stream)

                override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                    val receiver = stream as StreamOverNetty
                    val child = receiver.nettyChannel
                    child.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            while (msg.isReadable) events.add("byte:${msg.readUnsignedByte()}")
                        }

                        override fun channelReadComplete(ctx: ChannelHandlerContext) {
                            if (events.lastOrNull()?.startsWith("byte:") == true) events.add("complete")
                        }
                    })
                    val parent = child.parent()
                    parent.pipeline().addBefore(
                        parent.pipeline().context(AbstractMuxHandler::class.java).name(),
                        "pause-before-data",
                        object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                val size = when (msg) {
                                    is MplexFrame -> if (msg.id == child.id()) msg.data.readableBytes() else 0
                                    is YamuxFrame -> if (msg.id == child.id() && msg.type == YamuxType.DATA) msg.length.toInt() else 0
                                    else -> 0
                                }
                                if (size > 0) child.config().isAutoRead = false
                                ctx.fireChannelRead(msg)
                                if (size > 0) forwarded.add(size)
                            }
                        }
                    )
                    accepted.complete(receiver)
                    return CompletableFuture.completedFuture(stream)
                }
            }
        ) {}
        val mux = if (muxer == "mplex") StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, muxers -> NoiseXXSecureChannel(key, muxers) } }
            muxers { add(mux) }
            protocols { add(binding) }
        }
        val server = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, muxers -> NoiseXXSecureChannel(key, muxers) } }
            muxers { add(mux) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { add(binding) }
        }
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val sender = client.newStream<Stream>(
                listOf("/test/paused-child-manual-read/1.0.0"),
                server.peerId,
                server.listenAddresses().single()
            ).controller.get(5, TimeUnit.SECONDS)
            val child = accepted.get(5, TimeUnit.SECONDS).nettyChannel
            val parent = child.parent()
            child.config().isAutoRead = false
            parent.config().setRecvByteBufAllocator(io.netty.channel.FixedRecvByteBufAllocator(1))
            val callers = java.util.concurrent.Executors.newFixedThreadPool(8)
            val start = java.util.concurrent.CountDownLatch(1)
            try {
                val requests = (0 until 8).map {
                    callers.submit {
                        check(start.await(5, TimeUnit.SECONDS))
                        repeat(1000) { child.read() }
                    }
                }
                start.countDown()
                requests.forEach { it.get(5, TimeUnit.SECONDS) }
                parent.eventLoop().submit(Callable { Unit }).get(5, TimeUnit.SECONDS)
            } finally {
                start.countDown()
                callers.shutdownNow()
                assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
            }
            for (value in 1..2) {
                sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(value.toByte())))
                    .get(5, TimeUnit.SECONDS)
                // Mplex pauses the parent too. Admit the next physical read without resuming it.
                if (value == 2) parent.read()
                assertEquals(1, forwarded.poll(5, TimeUnit.SECONDS), "The payload must reach the paused child queue")
                parent.eventLoop().submit(Callable {
                    assertEquals(
                        listOf("byte:1", "complete"),
                        events.toList(),
                        "Concurrent pending reads authorize one payload, and must not authorize later arrivals"
                    )
                    if (value == 2) child.read()
                    assertEquals(
                        (1..value).flatMap { listOf("byte:$it", "complete") },
                        events.toList(),
                        "An explicit read must deliver queued bytes followed by read-complete"
                    )
                    assertFalse(child.config().isAutoRead, "Manual reading must leave automatic reads paused")
                    assertEquals(muxer == "yamux", parent.config().isAutoRead, "The parent's pause policy must be preserved")
                    assertTrue(child.isOpen)
                }).get(5, TimeUnit.SECONDS)
            }
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
