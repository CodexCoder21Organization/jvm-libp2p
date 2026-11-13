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
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class YamuxSizedReplacementTest {
    @ParameterizedTest
    @CsvSource("32,64", "128,64", "128,0")
    fun `final replacement size determines queue frame and returned budget`(originalSize: Int, replacementSize: Int) {
        val frames = SizedReplacementFrames()
        val handler = sizedReplacementMux()
        val channel = TestChannel("sized-replacement", true, frames, handler)
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val sibling = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val replacements = mutableListOf<ByteBuf>()
            stream.nettyChannel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    val original = msg as ByteBuf
                    val next = Unpooled.buffer(maxOf(1, replacementSize)).writeZero(replacementSize)
                    replacements += next
                    original.release()
                    ctx.write(next, promise)
                }
            })
            val original = Unpooled.buffer(originalSize).writeZero(originalSize)
            var completions = 0
            val caller = stream.nettyChannel.newPromise().also { it.addListener { completions++ } }
            // The empty replacement remains queued while its sibling uses the entire frame budget.
            stream.nettyChannel.write(original, caller)
            if (replacementSize > 0) stream.nettyChannel.flush()
            channel.runPendingTasks()
            assertThat(original.refCnt()).isZero()
            assertThat(caller.isDone).describedAs("A fitting replacement must remain accepted: %s", caller).isFalse()
            if (replacementSize == 0) {
                val boundary = sibling.writeAndFlushWithFuture(Unpooled.buffer(116).writeZero(116))
                channel.runPendingTasks()
                assertThat(boundary.isDone).describedAs("An empty queued replacement consumes no payload budget: %s", boundary).isFalse()
                assertThat(frames.held).hasSize(1)
                frames.completeNext()
                channel.runPendingTasks()
                assertThat(boundary).isCompletedWithValue(Unit)
                stream.nettyChannel.flush()
            } else {
                assertThat(frames.held).hasSize(1)
                assertThat(frames.held.single().first.length).isEqualTo(replacementSize.toLong())
                assertThat(ByteBufUtil.getBytes(frames.held.single().first.data!!)).containsExactly(*ByteArray(replacementSize))
                frames.completeNext()
            }
            channel.runPendingTasks()
            assertThat(caller.isSuccess).isTrue()
            assertThat(completions).isEqualTo(1)
            assertThat(replacements).hasSize(1).allMatch { it.refCnt() == 0 }
            assertThat(channel.isOpen).isTrue()
            // Exact payload admission proves no reservation survives completion.
            val budget = Unpooled.buffer(128).writeZero(128)
            val probe = sibling.nettyChannel.write(budget)
            channel.runPendingTasks()
            assertThat(probe.isDone).isFalse()
            assertThat(probe.cancel(false)).isTrue()
            sibling.nettyChannel.flush()
            channel.runPendingTasks()
            assertThat(budget.refCnt()).isZero()
            assertThat(frames.held).isEmpty()
            assertThat(completions).isEqualTo(1)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @ParameterizedTest
    @CsvSource("32,64", "128,64", "128,0")
    fun `unflushed admission charges the final payload and rejects one byte beyond it`(originalSize: Int, replacementSize: Int) {
        val frames = SizedReplacementFrames()
        val handler = sizedReplacementMux()
        val channel = TestChannel("sized-queue-boundary", true, frames, handler)
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val sibling = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val overflow = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val overflowId = frames.opened.last()
            val replacements = mutableListOf<ByteBuf>()
            stream.nettyChannel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    val original = msg as ByteBuf
                    val next = Unpooled.buffer(maxOf(1, replacementSize)).writeZero(replacementSize)
                    replacements += next
                    original.release()
                    ctx.write(next, promise)
                }
            })
            val original = Unpooled.buffer(originalSize).writeZero(originalSize)
            val first = stream.nettyChannel.write(original)
            val remaining = Unpooled.buffer(128 - replacementSize).writeZero(128 - replacementSize)
            val second = sibling.nettyChannel.write(remaining)
            channel.runPendingTasks()
            assertThat(first.isDone).isFalse()
            assertThat(second.isDone).describedAs("Final payloads totaling exactly 128 bytes must be admitted").isFalse()
            val extra = Unpooled.buffer(1).writeByte(1)
            val rejected = overflow.nettyChannel.write(extra)
            channel.runPendingTasks()
            assertThat(rejected.isDone).isTrue()
            assertThat(rejected.cause())
                .isInstanceOf(io.libp2p.mux.WriteBufferOverflowMuxerException::class.java)
                .hasMessage("Overflowed send buffer (129/128). Last stream attempting to write: $overflowId")
            assertThat(extra.refCnt()).isZero()
            // The reset frame may close a connection whose entire payload budget is queued.
            channel.close().get(5, TimeUnit.SECONDS)
            channel.runPendingTasks()
            assertThat(first.isDone).isTrue()
            assertThat(first.isSuccess).isFalse()
            assertThat(second.isDone).isTrue()
            assertThat(second.isSuccess).isFalse()
            assertThat(original.refCnt()).isZero()
            assertThat(remaining.refCnt()).isZero()
            assertThat(replacements).hasSize(1).allMatch { it.refCnt() == 0 }
            assertThat(frames.held).isEmpty()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `grown replacement waits for actual payload window credit`() {
        val frames = SizedReplacementFrames()
        val handler = YamuxHandler(
            MultistreamProtocolV1, 256, null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true, 128, DEFAULT_ACK_BACKLOG_LIMIT, 64
        )
        val channel = TestChannel("grown-window-replacement", true, frames, handler)
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val sibling = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS)
            val payload = ByteArray(96) { it.toByte() }
            val replacements = mutableListOf<ByteBuf>()
            stream.nettyChannel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    val original = msg as ByteBuf
                    val next = Unpooled.wrappedBuffer(payload)
                    replacements += next
                    original.release()
                    ctx.write(next, promise)
                }
            })
            val original = Unpooled.buffer(32).writeZero(32)
            var completions = 0
            val caller = stream.nettyChannel.newPromise().also { it.addListener { completions++ } }
            stream.nettyChannel.writeAndFlush(original, caller)
            channel.runPendingTasks()
            assertThat(caller.isDone).isFalse()
            assertThat(frames.held).hasSize(1)
            val id = frames.held.single().first.id
            assertThat(ByteBufUtil.getBytes(frames.held.single().first.data!!)).containsExactly(*payload.copyOfRange(0, 64))
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(caller.isDone).describedAs("The replacement's last 32 bytes still need window credit").isFalse()
            val siblingWrite = sibling.writeAndFlushWithFuture(Unpooled.buffer(64).writeZero(64))
            channel.runPendingTasks()
            assertThat(siblingWrite.isDone).isFalse()
            assertThat(frames.held).hasSize(1)
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(siblingWrite).isCompletedWithValue(Unit)
            assertThat(caller.isDone).isFalse()
            channel.writeInbound(YamuxFrame(id, YamuxType.WINDOW_UPDATE, YamuxFlag.NONE, 32))
            channel.runPendingTasks()
            assertThat(frames.held).hasSize(1)
            assertThat(ByteBufUtil.getBytes(frames.held.single().first.data!!)).containsExactly(*payload.copyOfRange(64, 96))
            assertThat(caller.isDone).isFalse()
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(caller.isSuccess).isTrue()
            assertThat(completions).isEqualTo(1)
            assertThat(original.refCnt()).isZero()
            assertThat(replacements).hasSize(1).allMatch { it.refCnt() == 0 }
            assertThat(channel.isOpen).isTrue()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["child", "parent"])
    fun `unflushed substituted entry releases on close`(close: String) {
        val frames = SizedReplacementFrames()
        val handler = sizedReplacementMux()
        val channel = TestChannel("unflushed-substitution-close", true, frames, handler)
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val sibling = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
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
            var completions = 0
            val caller = stream.nettyChannel.newPromise().also { it.addListener { completions++ } }
            stream.nettyChannel.write(original, caller)
            channel.runPendingTasks()
            assertThat(caller.isDone).isFalse()
            assertThat(original.refCnt()).isZero()
            assertThat(replacements).hasSize(1)
            assertThat(replacements.single().refCnt()).isPositive()
            assertThat(frames.held).isEmpty()
            if (close == "child") stream.close().get(5, TimeUnit.SECONDS) else channel.close().get(5, TimeUnit.SECONDS)
            channel.runPendingTasks()
            assertThat(caller.isDone).isTrue()
            assertThat(caller.isSuccess).isFalse()
            assertThat(completions).isEqualTo(1)
            assertThat(original.refCnt()).isZero()
            assertThat(replacements.single().refCnt()).isZero()
            stream.close().get(5, TimeUnit.SECONDS)
            if (close == "child") {
                assertThat(channel.isOpen).isTrue()
                val budget = Unpooled.buffer(128).writeZero(128)
                val probe = sibling.nettyChannel.write(budget)
                channel.runPendingTasks()
                assertThat(probe.isDone).describedAs("Child close returns the entire budget").isFalse()
                assertThat(probe.cancel(false)).isTrue()
                sibling.nettyChannel.flush()
                channel.runPendingTasks()
                assertThat(budget.refCnt()).isZero()
                val data = Unpooled.buffer(116).writeZero(116)
                val next = sibling.writeAndFlushWithFuture(data)
                channel.runPendingTasks()
                assertThat(next.isDone).isFalse()
                assertThat(frames.held).hasSize(1)
                assertThat(ByteBufUtil.getBytes(frames.held.single().first.data!!)).containsExactly(*ByteArray(116))
                frames.completeNext()
                channel.runPendingTasks()
                assertThat(next).isCompletedWithValue(Unit)
                assertThat(data.refCnt()).isZero()
            } else {
                assertThat(channel.isOpen).isFalse()
                assertThat(sibling.nettyChannel.isOpen).isFalse()
                val data = Unpooled.buffer(128).writeZero(128)
                val rejected = sibling.nettyChannel.writeAndFlush(data)
                channel.runPendingTasks()
                assertThat(rejected.isDone).isTrue()
                assertThat(rejected.isSuccess).isFalse()
                assertThat(data.refCnt()).isZero()
            }
            assertThat(completions).isEqualTo(1)
            assertThat(frames.held).isEmpty()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["success", "failure", "cancellation"])
    fun `copy slice composite replacement chain releases every reference`(outcome: String) {
        val frames = SizedReplacementFrames()
        val handler = sizedReplacementMux()
        val channel = TestChannel("replacement-chain", true, frames, handler)
        try {
            val stream = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val sibling = handler.createStream(StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
                .also { channel.runPendingTasks() }.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val replacements = mutableListOf<ByteBuf>()
            // addFirst reverses pipeline insertion; outbound traversal visits copy, slice, composite.
            for (kind in listOf("copy", "slice", "composite")) {
                stream.nettyChannel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                        val previous = msg as ByteBuf
                        val next = when (kind) {
                            "copy" -> previous.copy()
                            "slice" -> previous.retainedSlice()
                            else -> Unpooled.compositeBuffer(2).addComponents(
                                true, previous.retainedSlice(0, 32), previous.retainedSlice(32, 32)
                            )
                        }
                        replacements += next
                        previous.release()
                        ctx.write(next, promise)
                    }
                })
            }
            val expected = ByteArray(64) { it.toByte() }
            val original = Unpooled.wrappedBuffer(expected)
            var completions = 0
            val caller = stream.nettyChannel.newPromise().also { it.addListener { completions++ } }
            stream.nettyChannel.write(original, caller)
            channel.runPendingTasks()
            assertThat(replacements).hasSize(3)
            assertThat(original.refCnt()).isZero()
            assertThat(caller.isDone).isFalse()
            val failure = java.io.IOException("replacement chain parent write failed")
            if (outcome == "cancellation") {
                assertThat(caller.cancel(false)).isTrue()
            }
            stream.nettyChannel.flush()
            channel.runPendingTasks()
            if (outcome == "cancellation") {
                assertThat(frames.held).isEmpty()
                assertThat(caller.isCancelled).isTrue()
            } else {
                assertThat(frames.held).hasSize(1)
                assertThat(ByteBufUtil.getBytes(frames.held.single().first.data!!)).containsExactly(*expected)
                assertThat(caller.isDone).isFalse()
                if (outcome == "success") frames.completeNext() else frames.failNext(failure)
                channel.runPendingTasks()
                if (outcome == "success") assertThat(caller.isSuccess).isTrue() else assertThat(caller.cause()).isSameAs(failure)
            }
            assertThat(caller.isDone).isTrue()
            assertThat(completions).isEqualTo(1)
            assertThat(replacements).allMatch { it.refCnt() == 0 }
            assertThat(original.refCnt()).isZero()
            assertThat(channel.isOpen).isTrue()
            val budget = Unpooled.buffer(128).writeZero(128)
            val probe = sibling.nettyChannel.write(budget)
            channel.runPendingTasks()
            assertThat(probe.isDone).isFalse()
            assertThat(probe.cancel(false)).isTrue()
            sibling.nettyChannel.flush()
            channel.runPendingTasks()
            assertThat(budget.refCnt()).isZero()
            val next = sibling.writeAndFlushWithFuture(Unpooled.buffer(116).writeZero(116))
            channel.runPendingTasks()
            assertThat(next.isDone).isFalse()
            assertThat(frames.held).hasSize(1)
            frames.completeNext()
            channel.runPendingTasks()
            assertThat(next).isCompletedWithValue(Unit)
            assertThat(completions).isEqualTo(1)
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}

private fun sizedReplacementMux() = YamuxHandler(
    MultistreamProtocolV1,
    256,
    null,
    StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
    true,
    128,
    DEFAULT_ACK_BACKLOG_LIMIT,
    1024
)

private class SizedReplacementFrames : ChannelOutboundHandlerAdapter() {
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
