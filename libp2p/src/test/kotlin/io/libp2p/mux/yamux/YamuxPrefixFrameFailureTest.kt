package io.libp2p.mux.yamux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.tools.TestChannel
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

class YamuxPrefixFrameFailureTest {

    @Test
    fun `prefix frame failure promptly fails stalled application write and resets remainder`() {
        val frameFailure = FailFirstDataFrame()
        val handler = YamuxHandler(
            MultistreamProtocolV1,
            256,
            null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true,
            1 * 1024 * 1024,
            DEFAULT_ACK_BACKLOG_LIMIT,
            300
        )
        val channel = TestChannel("yamux-prefix-frame-failure", true, frameFailure, handler)
        val payload = Unpooled.buffer(301).writeZero(301)

        try {
            val streamPromise = handler.createStream(
                StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) }
            )
            channel.runPendingTasks()
            val stream = streamPromise.stream.get(5, TimeUnit.SECONDS)
            val synFrame = checkNotNull(channel.readOutbound<YamuxFrame>())
            assertThat(synFrame.flags).containsExactly(YamuxFlag.SYN)
            ReferenceCountUtil.release(synFrame)

            val writeFuture = stream.writeAndFlushWithFuture(payload)
            channel.runPendingTasks()

            assertThat(frameFailure.hasHeldFrame()).isTrue()
            assertThat(writeFuture.isDone).isFalse()

            val prefixFailure = IOException("parent frame write failed")
            frameFailure.failHeldFrame(prefixFailure)
            channel.runPendingTasks()

            assertThat(writeFuture.isDone).isTrue()
            assertThat(awaitFailure(writeFuture)).isSameAs(prefixFailure)

            val framesAfterFailure = drainOutboundFrames(channel)
            assertThat(framesAfterFailure.count { YamuxFlag.RST in it.flags }).isEqualTo(1)
            framesAfterFailure.forEach(ReferenceCountUtil::release)
            assertThat(payload.refCnt()).isZero()

            stream.close().get(5, TimeUnit.SECONDS)
            channel.runPendingTasks()
            val framesAfterClose = drainOutboundFrames(channel)
            assertThat(framesAfterClose.count { YamuxFlag.RST in it.flags }).isZero()
            framesAfterClose.forEach(ReferenceCountUtil::release)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun awaitFailure(future: CompletableFuture<Unit>): Throwable {
        return try {
            future.join()
            throw AssertionError("Expected the application write future to fail")
        } catch (failure: CompletionException) {
            failure.cause ?: failure
        }
    }

    private fun drainOutboundFrames(channel: TestChannel): List<YamuxFrame> = buildList {
        while (true) {
            add(channel.readOutbound<YamuxFrame>() ?: break)
        }
    }

    private class FailFirstDataFrame : ChannelOutboundHandlerAdapter() {
        private var heldFrame: Pair<YamuxFrame, ChannelPromise>? = null

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (
                heldFrame == null &&
                msg is YamuxFrame &&
                msg.type == YamuxType.DATA &&
                msg.data?.isReadable == true
            ) {
                heldFrame = msg to promise
            } else {
                ctx.write(msg, promise)
            }
        }

        fun hasHeldFrame(): Boolean = heldFrame != null

        fun failHeldFrame(cause: Throwable) {
            val (frame, promise) = checkNotNull(heldFrame) { "No Yamux data frame is awaiting failure" }
            heldFrame = null
            ReferenceCountUtil.release(frame)
            promise.tryFailure(cause)
        }

        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
            heldFrame?.let { (frame, heldPromise) ->
                ReferenceCountUtil.release(frame)
                heldPromise.tryFailure(IOException("channel closed while frame was held"))
            }
            heldFrame = null
            ctx.close(promise)
        }
    }
}
