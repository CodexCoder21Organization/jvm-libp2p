package io.libp2p.etc.util.netty

import io.netty.buffer.DefaultByteBufHolder
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.buffer.UnpooledHeapByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LoggingHandlerShortBoundedTest {
    @Test
    fun `large buffer formatting reads only the displayed bytes`() {
        val handler = LoggingHandlerShort(LogLevel.INFO)
        val channel = EmbeddedChannel(handler)
        var reads = 0
        val payload = object : UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, 32768, 32768) {
            override fun getByte(index: Int): Byte {
                reads++
                return super.getByte(index)
            }
        }
        payload.writeZero(payload.capacity())
        val readerIndex = payload.readerIndex()
        val writerIndex = payload.writerIndex()
        try {
            channel.writeOutbound(DefaultByteBufHolder(payload))
            assertThat(reads).isPositive()
            assertThat(reads).describedAs("hex formatting must read only heading and trailing rows").isLessThanOrEqualTo(2 * (handler.maxHeadingLines + handler.maxTrailingLines) * 16)
            assertThat(payload.readerIndex()).isEqualTo(readerIndex)
            assertThat(payload.writerIndex()).isEqualTo(writerIndex)
            assertThat(payload.refCnt()).isEqualTo(1)
        } finally {
            channel.finishAndReleaseAll()
        }
        assertThat(payload.refCnt()).isZero()
    }
}
