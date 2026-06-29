package io.libp2p.mux.mplex

import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toHex
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.tools.TestChannel
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class MplexHandlerTest : MuxHandlerAbstractTest() {

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

    /**
     * Regression test for the inbound-substream retention leak that OOM-crash-looped ContainerNursery
     * (UrlProtocol #294). A production heap dump (`-Xmx128m`) showed ~30,000 live MuxChannel /
     * Negotiator$ResponderHandler pipelines pinned by pending negotiation-timeout tasks — far past any
     * sane concurrency — because [AbstractMuxHandler.onRemoteOpen] built full per-substream scaffolding
     * for *every* inbound NEW_STREAM with no upper bound, and the per-substream negotiation-timeout that
     * would reclaim a never-completing inbound substream is a scheduled event-loop task that starves
     * under a sustained inbound flood. Neither the per-substream timeout nor connection-level autoRead
     * backpressure can bound the heap once the loop is saturated.
     *
     * This drives a remote that opens far more inbound substreams than the per-connection cap and
     * asserts that the mux layer (a) never holds more than the cap of live inbound substreams,
     * (b) never builds scaffolding for the excess, and (c) resets every refused substream back to the
     * peer. Without the [AbstractMuxHandler.maxInboundStreams] bound this FAILS: all `flood` substreams
     * are accepted (openInboundStreamCount == flood, no resets) — the unbounded accumulation that
     * exhausts the heap in production.
     */
    @Test
    fun inboundSubstreamsAreCappedAndExcessAreReset() {
        val cap = 8
        val flood = 200
        val built = AtomicInteger(0)
        val countingStreamHandler = StreamHandler<Unit> { _: Stream ->
            built.incrementAndGet()
            CompletableFuture.completedFuture(Unit)
        }
        val cappedHandler = object : MplexHandler(
            MultistreamProtocolV1,
            maxFrameDataLength,
            null,
            countingStreamHandler,
            cap
        ) {
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }
        val channel = TestChannel("capped-inbound", true, LoggingHandler(LogLevel.ERROR), cappedHandler)
        try {
            // Flood the connection with inbound NEW_STREAM frames (the reconnect/abort-herd dynamic).
            for (i in 1..flood) {
                val muxId = MplexId(channel.id(), i.toLong(), true)
                channel.writeInbound(
                    MplexFrame(muxId, MplexFlag.getByType(MplexFlag.Type.OPEN, true), Unpooled.EMPTY_BUFFER)
                )
            }
            channel.runPendingTasks()

            var resetFrames = 0
            while (true) {
                val frame = channel.readOutbound<MplexFrame>() ?: break
                if (frame.flag.type == MplexFlag.Type.RESET) resetFrames++
                frame.data.readAllBytesAndRelease()
            }

            assertThat(cappedHandler.openInboundStreamCount())
                .`as`("live inbound substreams must stay within the per-connection cap (heap bound)")
                .isLessThanOrEqualTo(cap)
            assertThat(built.get())
                .`as`("scaffolding must NOT be built for inbound substreams beyond the cap")
                .isLessThanOrEqualTo(cap)
            assertThat(cappedHandler.rejectedInboundStreamCount())
                .`as`("every inbound substream beyond the cap must be refused")
                .isEqualTo((flood - cap).toLong())
            assertThat(resetFrames)
                .`as`("every refused inbound substream must be reset back to the peer")
                .isEqualTo(flood - cap)
        } finally {
            channel.close()
        }
    }

    /**
     * The inbound-substream cap must be a *live* bound, not a one-way latch: when admitted inbound
     * substreams close (handled, reset, or negotiation timed out) their slots are released so a
     * legitimate peer can keep opening substreams once a surge subsides. This drives the connection
     * to the cap, confirms further opens are refused, closes some admitted substreams, then confirms
     * fresh opens are admitted again — the "returns to normal once the surge ends" contract.
     */
    @Test
    fun inboundSubstreamCapReleasesSlotsAsSubstreamsClose() {
        val cap = 4
        val built = AtomicInteger(0)
        val countingStreamHandler = StreamHandler<Unit> { _: Stream ->
            built.incrementAndGet()
            CompletableFuture.completedFuture(Unit)
        }
        val handler = object : MplexHandler(
            MultistreamProtocolV1,
            maxFrameDataLength,
            null,
            countingStreamHandler,
            cap
        ) {
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }
        val channel = TestChannel("capped-release", true, LoggingHandler(LogLevel.ERROR), handler)
        fun open(id: Long) = channel.writeInbound(
            MplexFrame(MplexId(channel.id(), id, true), MplexFlag.getByType(MplexFlag.Type.OPEN, true), Unpooled.EMPTY_BUFFER)
        )
        fun resetRemote(id: Long) = channel.writeInbound(
            MplexFrame(MplexId(channel.id(), id, true), MplexFlag.getByType(MplexFlag.Type.RESET, true), Unpooled.EMPTY_BUFFER)
        )
        fun drainOutbound() {
            while (channel.readOutbound<MplexFrame>() != null) { /* discard */ }
        }
        try {
            // Fill to the cap.
            for (id in 1L..cap) open(id)
            channel.runPendingTasks()
            assertThat(handler.openInboundStreamCount()).isEqualTo(cap)
            assertThat(built.get()).isEqualTo(cap)

            // Over the cap: refused.
            open(cap + 1L)
            channel.runPendingTasks()
            assertThat(handler.openInboundStreamCount()).isEqualTo(cap)
            assertThat(handler.rejectedInboundStreamCount()).isEqualTo(1L)
            drainOutbound()

            // The remote closes two admitted substreams -> two slots free up.
            resetRemote(1L)
            resetRemote(2L)
            channel.runPendingTasks()
            assertThat(handler.openInboundStreamCount())
                .`as`("closing admitted inbound substreams must release their admission slots")
                .isEqualTo(cap - 2)

            // Fresh opens are admitted again (recovery after the surge subsided).
            open(cap + 2L)
            open(cap + 3L)
            channel.runPendingTasks()
            assertThat(handler.openInboundStreamCount()).isEqualTo(cap)
            assertThat(built.get())
                .`as`("freed slots must let new inbound substreams be built again")
                .isEqualTo(cap + 2)
            assertThat(handler.rejectedInboundStreamCount())
                .`as`("opens that fit within freed slots must NOT be refused")
                .isEqualTo(1L)
        } finally {
            channel.close()
        }
    }
}
