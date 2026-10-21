package io.libp2p.mux

import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.mux.mplex.MplexFlag
import io.libp2p.mux.mplex.MplexFrame
import io.libp2p.mux.mplex.MplexHandler
import io.libp2p.mux.yamux.DEFAULT_ACK_BACKLOG_LIMIT
import io.libp2p.mux.yamux.DEFAULT_MAX_BUFFERED_CONNECTION_WRITES
import io.libp2p.mux.yamux.YamuxFlag
import io.libp2p.mux.yamux.YamuxFrame
import io.libp2p.mux.yamux.YamuxHandler
import io.libp2p.tools.TestChannel
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class StreamCloseWriteFramesTest {
    @ParameterizedTest(name = "repeated half-close emits one close frame, yamux={0}")
    @ValueSource(booleans = [true, false])
    fun repeatedHalfCloseEmitsOneFrame(yamux: Boolean) {
        withCloseWriteMux(yamux) { parent, stream ->
            stream.closeWrite().get(10, TimeUnit.SECONDS)
            assertEquals(listOf("close"), drainCloseWriteFrames(parent))
            assertDoesNotThrow { stream.closeWrite().get(10, TimeUnit.SECONDS) }
            assertEquals(emptyList<String>(), drainCloseWriteFrames(parent))
        }
    }

    @ParameterizedTest(name = "full close has no later close frame, yamux={0}")
    @ValueSource(booleans = [true, false])
    fun fullCloseEmitsNoLaterCloseFrame(yamux: Boolean) {
        withCloseWriteMux(yamux) { parent, stream ->
            stream.close().get(10, TimeUnit.SECONDS)
            assertEquals(listOf("reset"), drainCloseWriteFrames(parent))
            assertDoesNotThrow { stream.closeWrite().get(10, TimeUnit.SECONDS) }
            assertEquals(emptyList<String>(), drainCloseWriteFrames(parent))
        }
    }
}

private fun withCloseWriteMux(yamux: Boolean, scenario: (TestChannel, Stream) -> Unit) {
    val inbound = StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) }
    val mux = if (yamux) {
        YamuxHandler(
            MultistreamProtocolV1, 1024, null, inbound, true,
            DEFAULT_MAX_BUFFERED_CONNECTION_WRITES, DEFAULT_ACK_BACKLOG_LIMIT
        )
    } else {
        MplexHandler(MultistreamProtocolV1, 1024, null, inbound)
    }
    val parent = TestChannel("close-write-frames", true, mux)
    try {
        val opened = mux.createStream(StreamHandler<Stream> { CompletableFuture.completedFuture(it) })
        parent.runPendingTasks()
        val stream = opened.controller.get(10, TimeUnit.SECONDS)
        assertEquals(listOf("open"), drainCloseWriteFrames(parent))
        scenario(parent, stream)
    } finally {
        parent.finishAndReleaseAll()
    }
}

private fun drainCloseWriteFrames(parent: TestChannel): List<String> {
    val frames = mutableListOf<String>()
    parent.runPendingTasks()
    while (true) {
        val frame = parent.readOutbound<Any>() ?: break
        try {
            frames += when (frame) {
                is YamuxFrame -> when {
                    YamuxFlag.SYN in frame.flags -> "open"
                    YamuxFlag.FIN in frame.flags -> "close"
                    YamuxFlag.RST in frame.flags -> "reset"
                    else -> "other: $frame"
                }
                is MplexFrame -> when (frame.flag.type) {
                    MplexFlag.Type.OPEN -> "open"
                    MplexFlag.Type.CLOSE -> "close"
                    MplexFlag.Type.RESET -> "reset"
                    MplexFlag.Type.DATA -> "data"
                }
                else -> "other: $frame"
            }
        } finally {
            ReferenceCountUtil.release(frame)
        }
    }
    return frames
}
