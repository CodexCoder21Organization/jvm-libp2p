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
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture

class CoalescedProtocolSelectionTest {
    @ParameterizedTest
    @CsvSource("true, true", "false, true", "true, false", "false, false")
    fun selectedProtocolActivatesBeforeBufferedPayloadExactlyOnce(initiator: Boolean, coalesced: Boolean) {
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
                            // Initialization finishes inside the activation callback.
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
            if (coalesced) {
                channel.writeInbound(input)
            } else {
                while (input.isReadable) channel.writeInbound(input.readRetainedSlice(1))
            }
            assertTrue(channel.controllerFuture.isDone && !channel.controllerFuture.isCompletedExceptionally)
            val expectedEvents = if (coalesced) listOf("active", "payload") else listOf("active") + "payload".map { it.toString() }
            assertEquals(expectedEvents, events)
            assertTrue(channel.isOpen)
        } finally {
            if (!coalesced) input.release()
            channel.finishAndReleaseAll()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun activationCallbackCanReceiveDataThroughThePipeline(initiator: Boolean) {
        val received = mutableListOf<Byte>()
        val payload = byteArrayOf(0, 0, 0, 4, 65, 66, 67, 68)
        val protocol = "/test/reentrant-activation/1.0.0"
        val binding = object : StrictProtocolBinding<Stream>(
            protocol,
            object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> = onStartResponder(stream)

                override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                    stream.pushHandler(object : SimpleChannelInboundHandler<ByteBuf>() {
                        override fun channelActive(ctx: ChannelHandlerContext) {
                            ctx.pipeline().fireChannelRead(Unpooled.wrappedBuffer(payload))
                        }

                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            while (msg.isReadable) received.add(msg.readByte())
                        }
                    })
                    return CompletableFuture.completedFuture(stream)
                }
            }
        ) {}
        val channel = TestStreamChannel(initiator, binding)
        val headerBytes = "/multistream/1.0.0\n".toByteArray(CharsetUtil.UTF_8)
        val protocolBytes = "$protocol\n".toByteArray(CharsetUtil.UTF_8)
        try {
            channel.writeInbound(
                Unpooled.buffer().writeUvarint(headerBytes.size).writeBytes(headerBytes)
                    .writeUvarint(protocolBytes.size).writeBytes(protocolBytes)
            )
            assertTrue(channel.controllerFuture.isDone)
            assertEquals(payload.toList(), received, "the previous negotiation decoder must not consume the selected protocol's data")
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}
