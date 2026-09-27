package io.libp2p.mux

import io.libp2p.core.Host
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
import io.netty.channel.SimpleChannelInboundHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val PAUSE_PROTOCOL = "/test/child-read-pause/1.0.0"

class ChildReadPauseTcpTest {
    @ParameterizedTest
    @ValueSource(strings = ["mplex", "yamux"])
    fun serverConsumerCanPauseAndResumeRealMuxedStream(muxer: String) {
        val received = ConcurrentLinkedQueue<Int>()
        val delivered = CountDownLatch(2)
        val responder = CompletableFuture<Stream>()
        val binding = object : StrictProtocolBinding<Stream>(
            PAUSE_PROTOCOL,
            object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> =
                    CompletableFuture.completedFuture(stream)

                override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                    val channel = (stream as StreamOverNetty).nettyChannel
                    channel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            received.add(msg.readUnsignedByte().toInt())
                            delivered.countDown()
                        }
                    })
                    channel.config().isAutoRead = false
                    responder.complete(stream)
                    return CompletableFuture.completedFuture(stream)
                }
            }
        ) {}
        val mux = if (muxer == "mplex") StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        fun newHost(listen: Boolean): Host = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add(::PlaintextInsecureChannel) }
            muxers { add(mux) }
            if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { add(binding) }
        }

        val client = newHost(false)
        val server = newHost(true)
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val sender = client.newStream<Stream>(
                listOf(PAUSE_PROTOCOL),
                server.peerId,
                server.listenAddresses().single()
            ).controller.get(5, TimeUnit.SECONDS)
            val receiver = responder.get(5, TimeUnit.SECONDS) as StreamOverNetty
            assertFalse(receiver.nettyChannel.config().isAutoRead)

            sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(22))).get(5, TimeUnit.SECONDS)
            sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(44))).get(5, TimeUnit.SECONDS)
            receiver.nettyChannel.eventLoop().submit { assertTrue(received.isEmpty()) }.get(5, TimeUnit.SECONDS)

            receiver.nettyChannel.config().isAutoRead = true
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(22, 44), received.toList())
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
