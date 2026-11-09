package io.libp2p.protocol

import io.libp2p.core.Stream
import io.libp2p.etc.CONNECTION
import io.libp2p.tools.TestChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProtocolRetainedMessageReentryTest {
    @Test
    fun `nested callbacks each retain exactly their own reference`() {
        val channel = TestChannel("retained-protocol-message-reentry", true)
        val stream = StreamOverNetty(channel, channel.attr(CONNECTION).get(), true)
        val retained = mutableListOf<ByteBuf>()
        stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                retained += msg.retain()
                if (retained.size == 1) {
                    channel.writeInbound(Unpooled.buffer(4).writeInt(654321))
                }
            }
        })
        try {
            channel.writeInbound(Unpooled.buffer(4).writeInt(123456))
            assertThat(retained.map { it.getInt(0) }).containsExactly(123456, 654321)
            assertThat(retained.map { it.refCnt() }).containsExactly(1, 1)
            retained.forEach { assertThat(it.release()).isTrue() }
        } finally {
            retained.forEach { if (it.refCnt() > 0) it.release(it.refCnt()) }
            channel.finishAndReleaseAll()
        }
    }
}
