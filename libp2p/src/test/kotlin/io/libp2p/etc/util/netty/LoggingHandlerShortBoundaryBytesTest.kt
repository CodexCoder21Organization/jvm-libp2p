package io.libp2p.etc.util.netty

import io.libp2p.tools.TestLogAppender
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.buffer.UnpooledHeapByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LoggingHandlerShortBoundaryBytesTest {
    @Test
    fun `large raw buffer logs show boundaries without visiting hidden bytes`() {
        var reads = 0
        val buffer = object : UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, 32770, 32770) {
            override fun getByte(index: Int): Byte {
                reads++
                return super.getByte(index)
            }
        }
        buffer.writeZero(buffer.capacity())
        buffer.readerIndex(1)
        buffer.writerIndex(32769)
        buffer.setByte(1, 0x11)
        buffer.setByte(32768, 0x77)
        val handler = LoggingHandlerShort(LogLevel.INFO)
        val channel = EmbeddedChannel(handler)
        TestLogAppender().install().use { logs ->
            try {
                channel.writeOutbound(buffer)
                assertThat(reads).isPositive().isLessThanOrEqualTo(1920)
                val text = logs.logs.first { it.message.formattedMessage.contains("WRITE:") }.message.formattedMessage
                assertThat(text).contains("32768B", "11 00", "00 77", "more 31808 bytes")
                assertThat(buffer.readerIndex()).isEqualTo(1)
                assertThat(buffer.writerIndex()).isEqualTo(32769)
                assertThat(buffer.refCnt()).isEqualTo(1)
            } finally {
                channel.finishAndReleaseAll()
            }
        }
        assertThat(buffer.refCnt()).isZero()
    }
}
