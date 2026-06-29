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

    /**
     * Reproduces the ContainerNursery inbound-substream OOM (UrlProtocol #294;
     * kotlin.directory total outage 2026-06-29). A remote floods a SINGLE,
     * never-dropped connection with far more inbound stream OPENs than it ever
     * negotiates. Mplex has no flow control over the *number* of streams, so on the
     * unfixed responder every OPEN is tracked: each creates a [io.libp2p.etc.util.netty.mux.MuxChannel] +
     * multistream-negotiation pipeline + a pending 10s negotiation-timeout task that
     * is never reclaimed (the connection stays up, so the parent-close path that the
     * deployed fix relies on never fires). Production dumps showed ~500-720 such
     * streams per connection, exhausting the 128 MB heap.
     *
     * The responder must instead cap concurrently-open streams at
     * [MplexHandler.DEFAULT_MAX_INBOUND_STREAMS] and RESET every OPEN past the cap
     * BEFORE any scaffolding is built. FAILS on the unfixed handler (all
     * [floodSize] streams are tracked); PASSES once the cap is enforced.
     */
    @Test
    fun `inbound streams beyond the per-connection cap are reset, never accumulated`() {
        val cap = MplexHandler.DEFAULT_MAX_INBOUND_STREAMS
        val floodSize = cap * 4 // 1024 — the pathological "hundreds per connection" production flood

        repeat(floodSize) { openStreamRemote() }

        assertThat(childHandlers.size)
            .withFailMessage(
                "A single connection opened %d inbound streams but the responder tracked %d. " +
                    "Mplex has no per-connection stream limit, so a flood of inbound OPENs " +
                    "accumulates a MuxChannel + multistream-negotiation pipeline + a pending " +
                    "negotiation-timeout task each, exhausting the heap (ContainerNursery OOM / " +
                    "kotlin.directory outage, UrlProtocol #294). Concurrent inbound streams must " +
                    "be capped at %d.",
                floodSize,
                childHandlers.size,
                cap
            )
            .isLessThanOrEqualTo(cap)

        // Every over-cap OPEN must be answered with a RESET (so the remote learns the
        // stream was refused and our stream map never grows past the cap). Drain all
        // outbound frames; the only frames a bypassed-negotiation responder emits here
        // are these resets.
        var resets = 0
        while (true) {
            val frame = readFrame() ?: break
            if (frame.flag == Reset) resets++
        }
        assertThat(resets)
            .withFailMessage(
                "Expected the %d over-cap inbound OPENs to each be answered with a RESET frame, " +
                    "but saw %d. Without a RESET the remote keeps the stream id live and our " +
                    "stream map is never bounded.",
                floodSize - cap,
                resets
            )
            .isGreaterThanOrEqualTo(floodSize - cap)
    }

    /**
     * The cap bounds *concurrent* streams, not total streams over the connection's
     * lifetime: once an open stream closes and frees a slot, the next inbound OPEN
     * must be admitted again. Proves the fix does not permanently wedge a busy but
     * well-behaved connection. FAILS on the unfixed handler (the over-cap OPEN is
     * admitted in the first place, so childHandlers is already cap+1).
     */
    @Test
    fun `a slot freed by a closed stream lets a new inbound stream in`() {
        val cap = MplexHandler.DEFAULT_MAX_INBOUND_STREAMS
        val openIds = (0 until cap).map { openStreamRemote() }
        assertThat(childHandlers).hasSize(cap)

        // One more exceeds the cap: reset, not tracked.
        openStreamRemote()
        assertThat(childHandlers)
            .withFailMessage("An OPEN at the cap must be reset, not admitted; childHandlers=%d cap=%d", childHandlers.size, cap)
            .hasSize(cap)

        // Close an open stream, freeing exactly one slot.
        resetStream(openIds.first())

        // The next inbound OPEN is admitted again.
        openStreamRemote()
        assertThat(childHandlers)
            .withFailMessage(
                "After a stream closed and freed a slot, a new inbound stream must be admitted " +
                    "(the cap bounds CONCURRENT streams, not total). childHandlers=%d expected=%d",
                childHandlers.size,
                cap + 1
            )
            .hasSize(cap + 1)
    }

    override val maxFrameDataLength = 256

    override val localMuxIdGenerator = (0L..Long.MAX_VALUE).iterator()
    override val remoteMuxIdGenerator = (0L..Long.MAX_VALUE).iterator()

    override fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler =
        object : MplexHandler(
            MultistreamProtocolV1,
            maxFrameDataLength,
            null,
            streamHandler
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
}
