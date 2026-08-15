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

    @Test
    fun `small protobuf messages retain their useful field details`() {
        val rpc = Rpc.RPC.newBuilder()
            .addSubscriptions(
                Rpc.RPC.SubOpts.newBuilder()
                    .setSubscribe(true)
                    .setTopicid("small-topic")
            )
            .build()

        TestLogAppender().install().use { appender ->
            val channel = EmbeddedChannel(LoggingHandlerShort("small-protobuf-test", LogLevel.INFO))
            try {
                channel.writeOutbound(rpc)

                assertThat(appender.logs.map { it.message.formattedMessage })
                    .anyMatch { it.contains("topicid: \"small-topic\"") }
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }

    @Test
    fun `protobuf with exactly 1024 serialized bytes retains useful field details`() {
        val marker = "exact-1024-byte-payload-marker"
        val rpc = protobufWithSerializedSize(1024, marker)

        assertThat(rpc.serializedSize).isEqualTo(1024)
        TestLogAppender().install().use { appender ->
            val loggerName = "protobuf-1024-boundary-test"
            val channel = EmbeddedChannel(LoggingHandlerShort(loggerName, LogLevel.INFO))
            try {
                channel.writeOutbound(rpc)

                assertThat(
                    appender.logs
                        .filter { it.loggerName == loggerName }
                        .map { it.message.formattedMessage }
                ).anyMatch {
                    it.contains(marker) && it.contains("topicIDs: \"logging-boundary-topic\"")
                }
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }

    @Test
    fun `protobuf with exactly 1025 serialized bytes is summarized without its payload`() {
        val marker = "exact-1025-byte-payload-marker"
        val rpc = protobufWithSerializedSize(1025, marker)

        assertThat(rpc.serializedSize).isEqualTo(1025)
        TestLogAppender().install().use { appender ->
            val loggerName = "protobuf-1025-boundary-test"
            val channel = EmbeddedChannel(LoggingHandlerShort(loggerName, LogLevel.INFO))
            try {
                channel.writeOutbound(rpc)

                assertThat(
                    appender.logs
                        .filter { it.loggerName == loggerName }
                        .map { it.message.formattedMessage }
                ).anyMatch {
                    it.contains("WRITE: RPC(serializedSize=1025)") && !it.contains(marker)
                }
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }
}

private fun protobufWithSerializedSize(targetSize: Int, marker: String): Rpc.RPC {
    for (payloadSize in marker.length..targetSize) {
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(
                Rpc.Message.newBuilder()
                    .setData(ByteString.copyFromUtf8(marker + "x".repeat(payloadSize - marker.length)))
                    .addTopicIDs("logging-boundary-topic")
            )
            .build()
        if (rpc.serializedSize == targetSize) {
            return rpc
        }
        if (rpc.serializedSize > targetSize) {
            break
        }
    }
    throw IllegalStateException(
        "Could not construct a protobuf RPC with serialized size $targetSize using payload marker '$marker'"
    )
}
