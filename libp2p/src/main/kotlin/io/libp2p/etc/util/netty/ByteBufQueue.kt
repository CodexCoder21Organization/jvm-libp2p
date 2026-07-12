package io.libp2p.etc.util.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.util.ArrayDeque

class ByteBufQueue {
    private val data = ArrayDeque<ByteBuf>()

    fun push(buf: ByteBuf) {
        data.addLast(buf)
    }

    /**
     * Removes and returns up to [maxLength] bytes.
     *
     * @throws IllegalArgumentException if [maxLength] is negative.
     */
    fun take(maxLength: Int): ByteBuf {
        require(maxLength >= 0) { "maxLength must be non-negative, was $maxLength" }

        val buffers = mutableListOf<ByteBuf>()
        var size = 0
        while (data.isNotEmpty()) {
            val bufLen = data.getFirst().readableBytes()
            if (size + bufLen > maxLength) break
            size += bufLen
            buffers.add(data.removeFirst())
            if (size == maxLength) break
        }

        if (data.isNotEmpty() && size < maxLength) {
            val remainingBytes = maxLength - size
            buffers.add(data.getFirst().readRetainedSlice(remainingBytes))
        }

        return Unpooled.wrappedBuffer(*buffers.toTypedArray())
    }

    fun dispose() {
        data.forEach { it.release() }
    }

    fun readableBytes(): Int = data.sumOf { it.readableBytes() }
}
