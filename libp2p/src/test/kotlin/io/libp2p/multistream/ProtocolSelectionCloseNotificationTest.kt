package io.libp2p.multistream

import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.writeUvarint
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.tools.TestStreamChannel
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.util.CharsetUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.CompletableFuture

class ProtocolSelectionCloseNotificationTest {
    @ParameterizedTest
    @CsvSource("true, true", "false, true", "true, false", "false, false")
    fun selectedProtocolReceivesCloseFromItsCallbackExactlyOnce(initiator: Boolean, closeOnActivation: Boolean) {
        val events = mutableListOf<String>()
        val ready = CompletableFuture<Stream>()
        val protocol = "/test/close-during-selection/1.0.0"
        val binding = object : StrictProtocolBinding<Stream>(
            protocol,
            object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> = onStartResponder(stream)

                override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                    stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
                        override fun onActivated(stream: Stream) {
                            events.add("active")
                            ready.complete(stream)
                            if (closeOnActivation) stream.close()
                        }

                        override fun onMessage(stream: Stream, msg: ByteBuf) {
                            events.add(msg.toString(CharsetUtil.UTF_8))
                            stream.close()
                        }

                        override fun onClosed(stream: Stream) {
                            events.add("closed")
                        }
                    })
                    return ready
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
                    .writeBytes("payload".toByteArray(CharsetUtil.UTF_8))
            )
            channel.runPendingTasks()
            assertFalse(channel.isOpen)
            val expected = if (closeOnActivation) listOf("active", "closed") else listOf("active", "payload", "closed")
            assertEquals(expected, events)
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}
