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

class PausedChildResumeReadReentryTcpTest {
    @ParameterizedTest
    @ValueSource(strings = ["mplex", "yamux"])
    fun resumeCallbackCanPauseAndRequestDataAfterTheHeldQueueEmpties(muxer: String) {
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
                            if (events.count { it.startsWith("byte:") } == 1) {
                                child.config().isAutoRead = false
                                child.read()
                            }
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
            sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(1)))
                .get(5, TimeUnit.SECONDS)
            assertEquals(1, forwarded.poll(5, TimeUnit.SECONDS))
            // Finish the physical read before the independent automatic-resume drain starts.
            parent.eventLoop().submit(Callable { assertTrue(events.isEmpty()) }).get(5, TimeUnit.SECONDS)
            child.config().isAutoRead = true
            parent.eventLoop().submit(Callable {
                assertEquals(listOf("byte:1", "complete"), events)
                assertFalse(child.config().isAutoRead, "The payload callback must pause automatic delivery again")
            }).get(5, TimeUnit.SECONDS)
            sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(2)))
                .get(5, TimeUnit.SECONDS)
            val forwardedBytes = forwarded.poll(5, TimeUnit.SECONDS)
            assertTrue(forwardedBytes == 1, "Callback read demand must read the next physical payload, actual forwarded bytes=$forwardedBytes")
            parent.eventLoop().submit(Callable {
                assertEquals(listOf("byte:1", "complete", "byte:2", "complete"), events)
                assertFalse(child.config().isAutoRead)
                assertEquals(muxer == "yamux", parent.config().isAutoRead)
                assertTrue(child.isOpen)
            }).get(5, TimeUnit.SECONDS)
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
