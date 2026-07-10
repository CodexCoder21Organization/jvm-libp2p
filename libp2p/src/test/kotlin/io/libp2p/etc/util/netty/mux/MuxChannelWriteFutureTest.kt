package io.libp2p.etc.util.netty.mux

import io.libp2p.tools.TestChannel
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit

class MuxChannelWriteFutureTest {

    @Test
    fun `child write future waits for asynchronous parent write outcome`() {
        val muxHandler = AsyncParentWriteMuxHandler()
        val parentChannel = TestChannel("async-parent-write", true, muxHandler)
        val childFuture = muxHandler.newStream { }
        parentChannel.runPendingTasks()
        val child = childFuture.get(5, TimeUnit.SECONDS)

        val firstBuffer = Unpooled.buffer().writeByte(1)
        val firstFuture = child.writeAndFlush(firstBuffer)

        assertThat(muxHandler.acceptedWriteCount()).isEqualTo(1)
        assertThat(firstFuture.isDone).isFalse()

        val parentFailure = ClosedChannelException()
        muxHandler.failNextParentWrite(parentFailure)
        parentChannel.runPendingTasks()

        assertThat(firstFuture.isDone).isTrue()
        assertThat(firstFuture.isSuccess).isFalse()
        assertThat(firstFuture.cause()).isSameAs(parentFailure)
        assertThat(firstBuffer.refCnt()).isZero()
        parentChannel.finishAndReleaseAll()
    }

    private class AsyncParentWriteMuxHandler : AbstractMuxHandler<ByteBuf>() {
        private val parentWrites = ArrayDeque<ChannelPromise>()

        override val inboundInitializer: MuxChannelInitializer<ByteBuf> = { }

        override fun releaseMessage(msg: ByteBuf) {
            msg.release()
        }

        override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf): ChannelFuture {
            val promise = getChannelHandlerContext().newPromise()
            promise.addListener { ReferenceCountUtil.release(data) }
            parentWrites += promise
            return promise
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) = Unit

        override fun onLocalOpen(child: MuxChannel<ByteBuf>) = Unit

        override fun onLocalClose(child: MuxChannel<ByteBuf>) = Unit

        override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) = Unit

        override fun onChildClosed(child: MuxChannel<ByteBuf>) = Unit

        override fun generateNextId(): MuxId = TestMuxId(getChannelHandlerContext().channel().id(), 1)

        fun acceptedWriteCount(): Int = parentWrites.size

        fun failNextParentWrite(cause: Throwable) {
            parentWrites.removeAt(0).setFailure(cause)
        }
    }

    private class TestMuxId(parentId: io.netty.channel.ChannelId, id: Long) : MuxId(parentId, id) {
        override fun asShortText(): String = id.toString()

        override fun asLongText(): String = "${parentId.asShortText()}-$id"

        override fun equals(other: Any?): Boolean =
            other is TestMuxId && other.parentId == parentId && other.id == id

        override fun hashCode(): Int = 31 * parentId.hashCode() + id.hashCode()
    }
}
