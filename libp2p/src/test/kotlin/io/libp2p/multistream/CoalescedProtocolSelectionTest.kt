package io.libp2p.multistream

import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.writeUvarint
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.tools.TestStreamChannel
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.util.CharsetUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture

class CoalescedProtocolSelectionTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun selectedProtocolActivatesBeforeBufferedPayloadExactlyOnce(initiator: Boolean) {
        val events = mutableListOf<String>()
        val ready = CompletableFuture<Stream>()
        val protocol = "/test/activation-order/1.0.0"
        val binding = object : StrictProtocolBinding<Stream>(
            protocol,
            object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> = onStartResponder(stream)

                override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                    stream.pushHandler(object : SimpleChannelInboundHandler<ByteBuf>() {
                        override fun channelActive(ctx: ChannelHandlerContext) {
                            events.add("active")
                            // Completing initialization removes ProtocolSelect synchronously.
                            ready.complete(stream)
                        }

                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            events.add(msg.toString(CharsetUtil.UTF_8))
                        }
                    })
                    return ready
                }
            }
        ) {}
        val channel = TestStreamChannel(initiator, binding)
        val headerBytes = "/multistream/1.0.0\n".toByteArray(CharsetUtil.UTF_8)
        val protocolBytes = "$protocol\n".toByteArray(CharsetUtil.UTF_8)
        val input = Unpooled.buffer()
            .writeUvarint(headerBytes.size).writeBytes(headerBytes)
            .writeUvarint(protocolBytes.size).writeBytes(protocolBytes)
            .writeBytes("payload".toByteArray(CharsetUtil.UTF_8))
        try {
            channel.writeInbound(input)
            assertTrue(channel.controllerFuture.isDone)
            assertEquals(listOf("active", "payload"), events)
            assertTrue(channel.isOpen)
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}
