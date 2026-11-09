package io.libp2p.protocol

import io.libp2p.core.Stream
import io.libp2p.etc.CONNECTION
import io.libp2p.tools.TestChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ProtocolRetainedMessageFailureTest {
    @Test
    fun `a callback failure releases only the adapter reference`() {
        val channel = TestChannel("retained-protocol-message", true)
        val stream = StreamOverNetty(channel, channel.attr(CONNECTION).get(), true)
        val retained = CompletableFuture<ByteBuf>()
        val failure = CompletableFuture<Throwable?>()
        stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                retained.complete(msg.retain())
                throw IllegalStateException("The message callback could not finish")
            }
            override fun onException(cause: Throwable?) {
                failure.complete(cause)
            }
        })
        val input = Unpooled.buffer(4).writeInt(123456)
        try {
            channel.writeInbound(input)
            val owned = retained.get(5, TimeUnit.SECONDS)
            assertThat(failure.get(5, TimeUnit.SECONDS)).hasMessage("The message callback could not finish")
            assertThat(owned.readInt()).isEqualTo(123456)
            assertThat(owned.refCnt()).isEqualTo(1)
            assertThat(owned.release()).isTrue()
        } finally {
            // The test owns any remaining callback reference, including on the original implementation.
            if (input.refCnt() > 0) input.release(input.refCnt())
            channel.finishAndReleaseAll()
        }
    }
}
