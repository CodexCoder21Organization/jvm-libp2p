package io.libp2p.mux.yamux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.tools.TestChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class YamuxReplacedChildWriteTest {
    @ParameterizedTest
    @ValueSource(strings = ["slice", "composite", "copy"])
    fun `replacement completion returns active bytes before the next queued write`(replacement: String) {
        val frames = HeldReplacementFrames()
        val handler = YamuxHandler(
            MultistreamProtocolV1, 256, null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true, 128, DEFAULT_ACK_BACKLOG_LIMIT, 1024
        )
        val channel = TestChannel("replaced-child-write", true, frames, handler)
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            stream.nettyChannel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    val original = msg as ByteBuf
                    val size = original.readableBytes()
                    val next = when (replacement) {
                        "slice" -> original.retainedSlice()
                        "composite" -> Unpooled.compositeBuffer(2).addComponents(
                            true,
                            original.retainedSlice(original.readerIndex(), size / 2),
                            original.retainedSlice(original.readerIndex() + size / 2, size - size / 2)
                        )
                        "copy" -> original.copy()
                        else -> error("Unknown replacement: $replacement")
                    }
                    original.release()
                    ctx.write(next, promise)
                }
            })
            val first = Unpooled.buffer(64).writeZero(64)
            val second = Unpooled.buffer(64).writeZero(64)
            val firstWrite = stream.writeAndFlushWithFuture(first)
            val secondWrite = stream.writeAndFlushWithFuture(second)
            channel.runPendingTasks()
            assertThat(firstWrite.isDone).isFalse()
            assertThat(secondWrite.isDone).isFalse()
            assertThat(frames.held).hasSize(1)
            if (replacement == "copy") assertThat(first.refCnt()).isZero()
            assertThat(ByteBufUtil.getBytes(frames.held.single().first.data!!)).containsExactly(*ByteArray(64))

            frames.completeNext()
            channel.runPendingTasks()
            assertThat(firstWrite).isCompletedWithValue(Unit)
            assertThat(secondWrite.isDone)
                .describedAs("A replacement must leave the queued parent frame pending: %s", secondWrite)
                .isFalse()
            assertThat(frames.held).hasSize(1)
            assertThat(ByteBufUtil.getBytes(frames.held.single().first.data!!)).containsExactly(*ByteArray(64))
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(secondWrite).isCompletedWithValue(Unit)
            assertThat(first.refCnt()).isZero()
            assertThat(second.refCnt()).isZero()

            // The full budget is available again; its frame occupies exactly 128 bytes with the header.
            val boundary = Unpooled.buffer(116).writeZero(116)
            val boundaryWrite = stream.writeAndFlushWithFuture(boundary)
            channel.runPendingTasks()
            assertThat(boundaryWrite.isDone).isFalse()
            assertThat(frames.held).hasSize(1)
            assertThat(frames.held.single().first.length).isEqualTo(116)
            assertThat(ByteBufUtil.getBytes(frames.held.single().first.data!!)).containsExactly(*ByteArray(116))
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(boundaryWrite).isCompletedWithValue(Unit)
            assertThat(boundary.refCnt()).isZero()

            // A new control frame with no logical reservations verifies active bytes are zero.
            val fresh = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
            assertThat(fresh.stream.get(5, TimeUnit.SECONDS)).isNotNull()
            // Any positive leftover reservation would reject this exact full-budget unflushed write.
            val wholeBudgetData = Unpooled.buffer(128).writeZero(128)
            val wholeBudget = stream.nettyChannel.write(wholeBudgetData)
            channel.runPendingTasks()
            assertThat(wholeBudget.isDone).isFalse()
            assertThat(wholeBudget.cancel(false)).isTrue()
            stream.nettyChannel.flush()
            channel.runPendingTasks()
            assertThat(wholeBudgetData.refCnt()).isZero()
            assertThat(frames.held).isEmpty()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["frame failure", "child close", "parent close", "half close", "reentrant completion"])
    fun `replacement terminal paths leave the sibling budget usable`(outcome: String) {
        val frames = HeldReplacementFrames()
        val handler = YamuxHandler(
            MultistreamProtocolV1, 256, null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true, 128, DEFAULT_ACK_BACKLOG_LIMIT, 1024
        )
        val channel = TestChannel("replacement-terminal", true, frames, handler)
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val sibling = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .stream.get(5, TimeUnit.SECONDS)
            val replacements = mutableListOf<ByteBuf>()
            stream.nettyChannel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    val original = msg as ByteBuf
                    val next = original.copy()
                    replacements += next
                    original.release()
                    ctx.write(next, promise)
                }
            })
            val original = Unpooled.buffer(64).writeZero(64)
            val write = stream.writeAndFlushWithFuture(original)
            val queued = if (outcome == "parent close") {
                stream.writeAndFlushWithFuture(Unpooled.buffer(64).writeZero(64))
            } else null
            channel.runPendingTasks()
            assertThat(frames.held).hasSize(1)
            assertThat(original.refCnt()).isZero()
            if (outcome == "parent close") {
                assertThat(queued!!.isDone).isFalse()
                assertThat(replacements).hasSize(2)
            }
            var reentrant: CompletableFuture<Unit>? = null
            val frameFailure = java.io.IOException("replacement frame failed")
            when (outcome) {
                "frame failure" -> frames.failNext(frameFailure)
                "child close" -> stream.close().get(5, TimeUnit.SECONDS)
                "parent close" -> channel.close().get(5, TimeUnit.SECONDS)
                "half close" -> {
                    val close = stream.closeWrite()
                    frames.completeNext()
                    channel.runPendingTasks()
                    assertThat(close).isCompletedWithValue(Unit)
                }
                "reentrant completion" -> {
                    write.thenRun {
                        reentrant = sibling.writeAndFlushWithFuture(Unpooled.buffer(116).writeZero(116))
                    }
                    frames.completeNext()
                }
                else -> error("Unknown outcome: $outcome")
            }
            channel.runPendingTasks()
            if (outcome == "frame failure" || outcome == "child close" || outcome == "parent close") {
                assertThat(write.isCompletedExceptionally).isTrue()
                if (outcome == "frame failure") {
                    assertThat(write.handle { _, cause -> cause }.join()).isSameAs(frameFailure)
                }
            } else {
                assertThat(write).isCompletedWithValue(Unit)
            }
            if (outcome == "parent close") {
                assertThat(queued!!.isCompletedExceptionally).isTrue()
                assertThat(frames.held).isEmpty()
                assertThat(channel.isOpen).isFalse()
            } else {
                // Child close does not retract an already submitted physical frame.
                if (outcome == "child close") frames.completeNext()
                val boundary = if (outcome == "reentrant completion") {
                    assertThat(reentrant).describedAs("The completion callback must submit the sibling write").isNotNull()
                    checkNotNull(reentrant)
                } else {
                    sibling.writeAndFlushWithFuture(Unpooled.buffer(116).writeZero(116))
                }
                channel.runPendingTasks()
                assertThat(boundary.isDone)
                    .describedAs("The sibling must have the entire budget after $outcome")
                    .isFalse()
                assertThat(frames.held).hasSize(1)
                frames.completeNext()
                channel.runPendingTasks()
                assertThat(boundary).isCompletedWithValue(Unit)
                assertThat(channel.isOpen).isTrue()
            }
            assertThat(replacements).allMatch { it.refCnt() == 0 }
        } finally {
            channel.finishAndReleaseAll()
        }
    }


    @Test
    fun `batched replacement completes independently of the original reservations`() {
        val frames = HeldReplacementFrames()
        val handler = YamuxHandler(
            MultistreamProtocolV1, 256, null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true, 128, DEFAULT_ACK_BACKLOG_LIMIT, 1024
        )
        val channel = TestChannel("batched-replacement", true, frames, handler)
        val held = ArrayDeque<Triple<ChannelHandlerContext, ByteBuf, ChannelPromise>>()
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            stream.nettyChannel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    held.addLast(Triple(ctx, msg as ByteBuf, promise))
                }
            })
            val first = stream.writeAndFlushWithFuture(Unpooled.buffer(32).writeZero(32))
            val second = stream.writeAndFlushWithFuture(Unpooled.buffer(32).writeZero(32))
            channel.runPendingTasks()
            assertThat(held).hasSize(2)
            val a = held.first()
            val b = held.last()
            val composite = Unpooled.compositeBuffer(2).addComponents(
                true, a.second.retainedSlice(), b.second.retainedSlice()
            )
            val physical = a.first.writeAndFlush(composite)
            channel.runPendingTasks()
            assertThat(frames.held).hasSize(1)
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(physical.isSuccess).isTrue()
            assertThat(first.isDone).isFalse()
            assertThat(second.isDone).isFalse()
            a.second.release()
            held.removeFirst()
            a.third.setSuccess()
            channel.runPendingTasks()
            assertThat(first).isCompletedWithValue(Unit)

            val next = stream.writeAndFlushWithFuture(Unpooled.buffer(64).writeZero(64))
            channel.runPendingTasks()
            val c = held.removeFirst()
            val replacement = c.second.retainedSlice()
            c.second.release()
            c.first.writeAndFlush(replacement, c.third)
            channel.runPendingTasks()
            assertThat(next.isDone)
                .describedAs("Completed batch bytes must not remain active beside an original reservation")
                .isFalse()
            assertThat(frames.held).hasSize(1)
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(next).isCompletedWithValue(Unit)
            b.second.release()
            held.removeFirst()
            b.third.setSuccess()
            assertThat(second).isCompletedWithValue(Unit)
            val fresh = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
            assertThat(fresh.stream.get(5, TimeUnit.SECONDS)).isNotNull()
        } finally {
            held.forEach { (_, data, promise) ->
                ReferenceCountUtil.release(data)
                promise.tryFailure(io.libp2p.core.ConnectionClosedException("Batch fixture closed"))
            }
            channel.finishAndReleaseAll()
        }
    }


    @Test
    fun `replacement stays active until its flow controlled suffix completes`() {
        val frames = HeldReplacementFrames()
        val handler = YamuxHandler(
            MultistreamProtocolV1, 256, null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true, 128, DEFAULT_ACK_BACKLOG_LIMIT, 64
        )
        val channel = TestChannel("windowed-replacement", true, frames, handler)
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            stream.nettyChannel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    val original = msg as ByteBuf
                    val next = Unpooled.compositeBuffer(2).addComponents(
                        true, original.retainedSlice(0, 48), original.retainedSlice(48, 48)
                    )
                    original.release()
                    ctx.write(next, promise)
                }
            })
            val data = Unpooled.buffer(96).writeZero(96)
            val write = stream.writeAndFlushWithFuture(data)
            channel.runPendingTasks()
            assertThat(frames.held).hasSize(1)
            val id = frames.held.single().first.id
            assertThat(frames.held.single().first.length).isEqualTo(64)
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(write.isDone).isFalse()
            assertThat(data.refCnt()).isPositive()
            channel.writeInbound(YamuxFrame(id, YamuxType.WINDOW_UPDATE, YamuxFlag.NONE, 32))
            channel.runPendingTasks()
            assertThat(frames.held).hasSize(1)
            assertThat(frames.held.single().first.length).isEqualTo(32)
            assertThat(write.isDone).isFalse()
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(write).isCompletedWithValue(Unit)
            assertThat(data.refCnt()).isZero()
            val sibling = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .stream.get(5, TimeUnit.SECONDS)
            val probe = sibling.writeAndFlushWithFuture(Unpooled.buffer(64).writeZero(64))
            channel.runPendingTasks()
            assertThat(probe.isDone).isFalse()
            assertThat(frames.held).hasSize(1)
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(probe).isCompletedWithValue(Unit)
        } finally {
            channel.finishAndReleaseAll()
        }
    }


    @Test
    fun `replacement reservations still reject one byte over the connection budget`() {
        val frames = HeldReplacementFrames()
        val handler = YamuxHandler(
            MultistreamProtocolV1, 256, null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true, 128, DEFAULT_ACK_BACKLOG_LIMIT, 1024
        )
        val channel = TestChannel("replacement-budget", true, frames, handler)
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val overflow = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .stream.get(5, TimeUnit.SECONDS)
            val overflowId = frames.opened.last()
            stream.nettyChannel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    val original = msg as ByteBuf
                    val next = original.copy()
                    original.release()
                    ctx.write(next, promise)
                }
            })
            val first = stream.writeAndFlushWithFuture(Unpooled.buffer(64).writeZero(64))
            val second = stream.writeAndFlushWithFuture(Unpooled.buffer(64).writeZero(64))
            val extra = Unpooled.buffer(1).writeByte(1)
            val rejected = overflow.writeAndFlushWithFuture(extra)
            channel.runPendingTasks()
            assertThat(rejected.isCompletedExceptionally).isTrue()
            assertThat(rejected.handle { _, cause -> cause }.join())
                .isInstanceOf(io.libp2p.mux.WriteBufferOverflowMuxerException::class.java)
                .hasMessage("Overflowed send buffer (129/128). Last stream attempting to write: $overflowId")
            assertThat(extra.refCnt()).isZero()
            assertThat(first.isDone).isFalse()
            assertThat(second.isDone).isFalse()
            assertThat(channel.isOpen).isTrue()
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(first).isCompletedWithValue(Unit)
            assertThat(second.isDone).isFalse()
            assertThat(frames.held).hasSize(1)
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(second).isCompletedWithValue(Unit)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

}

private class HeldReplacementFrames : ChannelOutboundHandlerAdapter() {
    val held = ArrayDeque<Pair<YamuxFrame, ChannelPromise>>()
    val opened = mutableListOf<io.libp2p.etc.util.netty.mux.MuxId>()

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is YamuxFrame && msg.data?.isReadable == true) {
            held.addLast(msg to promise)
        } else {
            if (msg is YamuxFrame && YamuxFlag.SYN in msg.flags) opened += msg.id
            ReferenceCountUtil.release(msg)
            promise.setSuccess()
        }
    }

    fun failNext(cause: Throwable) {
        val (frame, promise) = held.removeFirst()
        ReferenceCountUtil.release(frame)
        promise.setFailure(cause)
    }

    fun completeNext() {
        val (frame, promise) = held.removeFirst()
        ReferenceCountUtil.release(frame)
        promise.setSuccess()
    }

    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        while (held.isNotEmpty()) {
            val (frame, pending) = held.removeFirst()
            ReferenceCountUtil.release(frame)
            pending.tryFailure(io.libp2p.core.ConnectionClosedException("Parent closed with a held replacement frame"))
        }
        ctx.close(promise)
    }
}
