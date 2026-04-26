package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toHex
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MplexHandlerTest : MuxHandlerAbstractTest() {

    override val maxFrameDataLength = 256
    val maxOpenInboundStreams = 8

    override val localMuxIdGenerator = (0L..Long.MAX_VALUE).iterator()
    override val remoteMuxIdGenerator = (0L..Long.MAX_VALUE).iterator()

    override fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler =
        object : MplexHandler(
            MultistreamProtocolV1,
            maxFrameDataLength,
            null,
            streamHandler,
            maxOpenInboundStreams
        ) {
            // MuxHandler consumes the exception. Override this behaviour for testing
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

    override fun writeFrame(frame: AbstractTestMuxFrame) {
        val muxId = MplexId(parentChannelId, frame.streamId, true)
        val mplexFlag = when (frame.flag) {
            Open -> MplexFlag.Type.OPEN
            Data -> MplexFlag.Type.DATA
            Close -> MplexFlag.Type.CLOSE
            Reset -> MplexFlag.Type.RESET
        }
        val data = when {
            frame.data.isEmpty() -> Unpooled.EMPTY_BUFFER
            else -> frame.data.fromHex().toByteBuf(allocateBuf())
        }
        val mplexFrame =
            MplexFrame(muxId, MplexFlag.getByType(mplexFlag, true), data)
        ech.writeInbound(mplexFrame)
    }

    override fun readFrame(): AbstractTestMuxFrame? {
        val maybeMplexFrame = ech.readOutbound<MplexFrame>()
        return maybeMplexFrame?.let { mplexFrame ->
            val flag = when (mplexFrame.flag.type) {
                MplexFlag.Type.OPEN -> Open
                MplexFlag.Type.DATA -> Data
                MplexFlag.Type.CLOSE -> Close
                MplexFlag.Type.RESET -> Reset
            }
            val data = maybeMplexFrame.data.readAllBytesAndRelease().toHex()
            AbstractTestMuxFrame(mplexFrame.id.id, flag, data)
        }
    }

    /**
     * Reproduces the production OOM where a misbehaving peer opens many inbound mplex
     * streams and never finishes multistream-select. Without a per-connection cap,
     * every accepted stream pins a Netty pipeline + a slice of a Netty PoolChunk,
     * eventually exhausting the JVM's default 128 MB direct buffer pool.
     *
     * Yamux already enforces a similar bound via `ackBacklogLimit`, so this case is
     * mplex-specific and the test lives here.
     */
    @Test
    fun `inbound stream cap is enforced`() {
        val cap = maxOpenInboundStreams

        val acceptedIds = mutableListOf<Long>()
        for (i in 1..cap) {
            acceptedIds += openStreamRemote()
        }
        assertHandlerCount(cap)

        val rejectedId = remoteMuxIdGenerator.next()
        openStreamRemote(rejectedId)

        // No new child handler — streamMap must not grow past the cap.
        assertHandlerCount(cap)

        // The muxer must have written a RESET frame back to the peer for the
        // rejected stream id.
        val outboundFrames = generateSequence { readFrame() }.toList()
        val resetForRejected = outboundFrames.firstOrNull {
            it.streamId == rejectedId && it.flag == Reset
        }
        assertThat(resetForRejected)
            .withFailMessage(
                "Expected a RESET frame for stream id=$rejectedId after exceeding " +
                    "maxOpenInboundStreams=$cap. Outbound frames: $outboundFrames"
            )
            .isNotNull

        // Connection itself must remain open — exceeding the per-connection cap is
        // not a connection-level fatal error.
        assertThat(ech.isOpen).isTrue()

        // Each previously-accepted stream must still be operational.
        writeStream(acceptedIds.first(), "22")
        assertThat(childHandlers.first().inboundMessages).contains("22")
    }
}
