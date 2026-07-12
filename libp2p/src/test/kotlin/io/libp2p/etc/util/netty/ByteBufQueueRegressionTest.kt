package io.libp2p.etc.util.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Test
import java.time.Duration

class ByteBufQueueRegressionTest {

    @Test
    fun `take at exact max length preserves bytes and releases consumed buffers`() {
        val queue = ByteBufQueue()
        val inputs = listOf(retainedInput("ab"), retainedInput("cd"))
        inputs.forEach(queue::push)

        try {
            assertThat(takeBytes(queue, 4)).containsExactly(*bytes("abcd"))
            assertThat(queue.readableBytes()).isZero()
            assertThat(inputs.map(ByteBuf::refCnt)).containsExactly(1, 1)
        } finally {
            releaseExternalReferences(queue, inputs)
        }
    }

    @Test
    fun `partial take spanning buffers preserves unread bytes and reference counts`() {
        val queue = ByteBufQueue()
        val inputs = listOf(retainedInput("ab"), retainedInput("cdef"), retainedInput("gh"))
        inputs.forEach(queue::push)

        try {
            assertThat(takeBytes(queue, 4)).containsExactly(*bytes("abcd"))
            assertThat(queue.readableBytes()).isEqualTo(4)
            assertThat(inputs.map(ByteBuf::refCnt)).containsExactly(1, 2, 2)
            assertThat(takeBytes(queue, 4)).containsExactly(*bytes("efgh"))
            assertThat(inputs.map(ByteBuf::refCnt)).containsExactly(1, 1, 1)
        } finally {
            releaseExternalReferences(queue, inputs)
        }
    }

    @Test
    fun `take larger than queue preserves all bytes`() {
        val queue = ByteBufQueue()
        val inputs = listOf(retainedInput("abc"), retainedInput("def"))
        inputs.forEach(queue::push)

        try {
            assertThat(takeBytes(queue, 100)).containsExactly(*bytes("abcdef"))
            assertThat(queue.readableBytes()).isZero()
            assertThat(inputs.map(ByteBuf::refCnt)).containsExactly(1, 1)
        } finally {
            releaseExternalReferences(queue, inputs)
        }
    }

    @Test
    fun `single byte buffers drain frame at a time without changing bytes`() {
        val queue = ByteBufQueue()
        val inputs = "libp2p".map { retainedInput(it.toString()) }
        inputs.forEach(queue::push)

        try {
            val drained = ByteArray(inputs.size) { takeBytes(queue, 1).single() }
            assertThat(drained).containsExactly(*bytes("libp2p"))
            assertThat(queue.readableBytes()).isZero()
            assertThat(inputs.map(ByteBuf::refCnt)).containsOnly(1)
        } finally {
            releaseExternalReferences(queue, inputs)
        }
    }

    @Test
    fun `empty queue returns an empty buffer`() {
        val queue = ByteBufQueue()

        assertThat(takeBytes(queue, 1024)).isEmpty()
        assertThat(queue.readableBytes()).isZero()
        queue.dispose()
    }

    @Test
    fun `negative max length leaves queued bytes and reference counts unchanged`() {
        val queue = ByteBufQueue()
        val inputs = listOf(retainedInput("abc"), retainedInput("def"))
        inputs.forEach(queue::push)

        try {
            val refCountsBefore = inputs.map(ByteBuf::refCnt)

            val exception = assertThrows(IllegalArgumentException::class.java) { queue.take(-1) }

            assertThat(exception).hasMessage("maxLength must be non-negative, was -1")
            assertThat(queue.readableBytes()).isEqualTo(6)
            assertThat(inputs.map(ByteBuf::refCnt)).isEqualTo(refCountsBefore)
            assertThat(takeBytes(queue, 6)).containsExactly(*bytes("abcdef"))
        } finally {
            releaseExternalReferences(queue, inputs)
        }
    }

    @Test
    fun `interleaved push and take preserves order and dispose releases queued buffers`() {
        val queue = ByteBufQueue()
        val first = retainedInput("abc")
        val second = retainedInput("def")
        val third = retainedInput("ghi")
        val inputs = listOf(first, second, third)
        queue.push(first)

        try {
            assertThat(takeBytes(queue, 2)).containsExactly(*bytes("ab"))
            queue.push(second)
            assertThat(takeBytes(queue, 3)).containsExactly(*bytes("cde"))
            queue.push(third)
            assertThat(queue.readableBytes()).isEqualTo(4)
            assertThat(takeBytes(queue, 2)).containsExactly(*bytes("fg"))
            assertThat(queue.readableBytes()).isEqualTo(2)
        } finally {
            releaseExternalReferences(queue, inputs)
        }
    }

    @Test
    fun `frame at a time drain scales linearly`() {
        val queue = ByteBufQueue()
        val bufferCount = 1_000_000
        repeat(bufferCount) {
            queue.push(Unpooled.wrappedBuffer(byteArrayOf((it and 0xff).toByte())))
        }

        try {
            var checksum = 0
            // Yamux drains its ordered outbound buffer one frame at a time.
            assertTimeout(Duration.ofSeconds(5)) {
                repeat(bufferCount) {
                    val frame = queue.take(1)
                    try {
                        checksum += frame.readUnsignedByte()
                    } finally {
                        frame.release()
                    }
                }
            }
            assertThat(checksum).isEqualTo((0 until bufferCount).sumOf { it and 0xff })
            assertThat(queue.readableBytes()).isZero()
        } finally {
            queue.dispose()
        }
    }

    private fun retainedInput(value: String): ByteBuf =
        Unpooled.wrappedBuffer(bytes(value)).retain()

    private fun takeBytes(queue: ByteBufQueue, maxLength: Int): ByteArray {
        val result = queue.take(maxLength)
        return try {
            ByteArray(result.readableBytes()).also(result::readBytes)
        } finally {
            result.release()
        }
    }

    private fun releaseExternalReferences(queue: ByteBufQueue, inputs: List<ByteBuf>) {
        queue.dispose()
        inputs.forEach {
            assertThat(it.refCnt()).isEqualTo(1)
            assertThat(it.release()).isTrue()
            assertThat(it.refCnt()).isZero()
        }
    }

    private fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
}
