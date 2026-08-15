package io.libp2p.etc.util.netty

import com.google.protobuf.ByteString
import io.libp2p.tools.TestLogAppender
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class LoggingHandlerShortTest {

    @Test
    fun `protobuf messages are logged without rendering their bytes fields`() {
        val marker = "large-payload-marker"
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(
                Rpc.Message.newBuilder()
                    .setData(ByteString.copyFromUtf8(marker.repeat(100)))
                    .addTopicIDs("topic")
            )
            .build()

        TestLogAppender().install().use { appender ->
            val channel = EmbeddedChannel(LoggingHandlerShort("short-protobuf-test", LogLevel.INFO))
            try {
                channel.writeOutbound(rpc)

                assertThat(appender.logs.map { it.message.formattedMessage })
                    .anyMatch {
                        it.contains("WRITE: RPC(serializedSize=${rpc.serializedSize})") &&
                            !it.contains(marker)
                    }
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }
}
