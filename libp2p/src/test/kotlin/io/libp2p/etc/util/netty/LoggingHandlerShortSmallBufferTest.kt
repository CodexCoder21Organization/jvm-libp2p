package io.libp2p.etc.util.netty

import io.libp2p.tools.TestLogAppender
import io.netty.buffer.DefaultByteBufHolder
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LoggingHandlerShortSmallBufferTest {
    @Test
    fun `small and simple buffer logs keep Netty formatting`() {
        TestLogAppender().install().use { logs ->
            for (simple in listOf(false, true)) {
                for (holder in listOf(false, true)) {
                    val format = if (simple) ByteBufFormat.SIMPLE else ByteBufFormat.HEX_DUMP
                    val short = LoggingHandlerShort(LogLevel.INFO, format)
                    val channel = EmbeddedChannel(short)
                    val buffer = Unpooled.buffer().writeZero(if (simple) 32768 else 32)
                    val message = if (holder) DefaultByteBufHolder(buffer) else buffer
                    try {
                        logs.logs.clear()
                        channel.writeOutbound(message)
                        val shortened = logs.logs.first { it.message.formattedMessage.contains("WRITE:") }.message.formattedMessage
                        channel.pipeline().replace(short, "plain", LoggingHandler(LogLevel.INFO, format))
                        logs.logs.clear()
                        buffer.retain()
                        channel.writeOutbound(message)
                        val plain = logs.logs.first { it.message.formattedMessage.contains("WRITE:") }.message.formattedMessage
                        assertThat(shortened).isEqualTo(plain)
                        assertThat(buffer.readerIndex()).isZero()
                        assertThat(buffer.writerIndex()).isEqualTo(if (simple) 32768 else 32)
                    } finally {
                        channel.finishAndReleaseAll()
                    }
                    assertThat(buffer.refCnt()).isZero()
                }
            }
        }
    }
}
