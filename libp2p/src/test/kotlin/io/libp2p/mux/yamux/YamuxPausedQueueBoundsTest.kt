package io.libp2p.mux.yamux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.libp2p.tools.TestChannel
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

private const val pausedQueueTestFrameLimit = 64
private const val pausedQueueTestFrameSize = 256
private const val pausedQueueTestWindowSize = 300

class YamuxPausedQueueBoundsTest {

    @Test
    fun pausedChildRetainsAtMost64ExactSizedFrameCopies() {
        QueueBoundsFixture().use { fixture ->
            val child = fixture.openRemoteChild()
            child.channel.config().isAutoRead = false

            repeat(63) { fixture.sendData(child, byteArrayOf(it.toByte())) }
            assertRetainedCopyBound(fixture, expectedFrames = 63, expectedBytes = 63)

            fixture.sendData(child, byteArrayOf(63))
            assertRetainedCopyBound(fixture, expectedFrames = 64, expectedBytes = 64)
            assertThat(child.channel.isOpen).isTrue()
            assertThat(fixture.parent.config().isAutoRead).isTrue()
            assertThat(child.received).isEmpty()
        }
    }

    @Test
    fun sixtyFifthPausedFrameResetsStreamAndReleasesEveryRetainedCopy() {
        QueueBoundsFixture().use { fixture ->
            val child = fixture.openRemoteChild()
            child.channel.config().isAutoRead = false
            repeat(65) { fixture.sendData(child, byteArrayOf(it.toByte())) }

            assertThat(child.channel.isOpen).isFalse()
            assertThat(child.exceptions).hasSize(1)
            val expectedMessage =
                "Paused child ${child.channel.id().asLongText()} inbound queue exceeded: " +
                    "attempted 65 frames and 65 bytes; limits are 64 frames and 300 bytes; resetting the stream"
            assertThat(child.exceptions.single())
                .isInstanceOf(ConnectionClosedException::class.java)
                .hasMessage(expectedMessage)
            assertThat(fixture.retainedCopies).hasSize(64)
            assertThat(fixture.retainedCopies).allMatch { it.refCnt() == 0 }
            assertThat(child.received).isEmpty()
            assertThat(
                fixture.outboundFrames.any { frame ->
                    frame.streamId == child.streamId &&
                        frame.type == YamuxType.DATA &&
                        frame.flags == YamuxFlag.RST.asSet
                }
            ).isTrue()
            val rejectedWrite = Unpooled.wrappedBuffer(byteArrayOf(0x33))
            val subsequentFailure = child.stream.writeAndFlushWithFuture(rejectedWrite)
                .handle { _, failure -> unwrapCompletionFailure(failure) }
            fixture.parent.runPendingTasks()
            assertThat(subsequentFailure.join())
                .isInstanceOf(ConnectionClosedException::class.java)
                .hasMessage(expectedMessage)
            assertThat(rejectedWrite.refCnt()).isZero()
            assertThat(fixture.parent.config().isAutoRead).isTrue()
            assertInputBuffersReleased(fixture)
        }
    }

    @Test
    fun coalescedOverflowStillDeliversSiblingFrameAndKeepsParentReading() {
        QueueBoundsFixture().use { fixture ->
            val paused = fixture.openRemoteChild()
            val sibling = fixture.openRemoteChild()
            paused.channel.config().isAutoRead = false
            fixture.drainOutbound()

            val coalescedFrames = 256 // Fits the byte window but far exceeds the frame limit.
            val wireBytes = (coalescedFrames + 1) * (12 + 1)
            val coalesced = Unpooled.buffer(wireBytes, wireBytes)
            repeat(coalescedFrames) { index ->
                appendYamuxFrame(coalesced, paused.streamId, YamuxFlag.NONE, byteArrayOf(index.toByte()))
            }
            appendYamuxFrame(coalesced, sibling.streamId, YamuxFlag.NONE, byteArrayOf(0x55))
            fixture.writeWire(coalesced)
            fixture.parent.runPendingTasks()

            assertThat(paused.channel.isOpen).isFalse()
            assertThat(sibling.channel.isOpen).isTrue()
            assertThat(sibling.received.map { it.single() }).containsExactly(0x55.toByte())
            assertThat(paused.received).isEmpty()
            assertThat(fixture.parent.config().isAutoRead).isTrue()
            assertThat(fixture.retainedCopies).hasSize(pausedQueueTestFrameLimit)
            assertThat(fixture.retainedCopies).allMatch { it.refCnt() == 0 }
        }
    }

    @Test
    fun sixteenPausedStreamsRetainOnlyTheirExactQueuedPayloadBytes() {
        QueueBoundsFixture().use { fixture ->
            val children = List(16) { fixture.openRemoteChild() }
            children.forEach { it.channel.config().isAutoRead = false }

            children.forEach { child ->
                repeat(pausedQueueTestFrameLimit) { index ->
                    val bytes = if (index == pausedQueueTestFrameLimit - 1) 48 else 4
                    fixture.sendData(child, ByteArray(bytes) { index.toByte() })
                }
            }

            val retained = fixture.activeRetainedCopies()
            val retainedBytes = retained.sumOf { it.capacity().toLong() }
            assertThat(retained).hasSize(16 * pausedQueueTestFrameLimit)
            assertThat(retained.count { it.capacity() == 4 }).isEqualTo(16 * 63)
            assertThat(retained.count { it.capacity() == 48 }).isEqualTo(16)
            assertThat(retainedBytes).isEqualTo(16L * pausedQueueTestWindowSize)
            assertThat(retainedBytes).isLessThanOrEqualTo(16L * pausedQueueTestWindowSize)
            assertThat(children.all { it.received.isEmpty() }).isTrue()

            fixture.closeParent()
            assertThat(fixture.retainedCopies).allMatch { it.refCnt() == 0 }
            assertThat(children).allMatch { !it.channel.isOpen }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["remote reset", "local close", "parent close"])
    fun closingAtTheFullFrameBoundReleasesQueuedCopies(closeMode: String) {
        QueueBoundsFixture().use { fixture ->
            val children = List(2) { fixture.openRemoteChild() }
            children.forEach { it.channel.config().isAutoRead = false }
            children.forEach { child ->
                repeat(pausedQueueTestFrameLimit) { fixture.sendData(child, byteArrayOf(0x42)) }
            }
            val firstCopies = fixture.retainedCopies.take(pausedQueueTestFrameLimit)
            val secondCopies = fixture.retainedCopies.drop(pausedQueueTestFrameLimit)
            assertThat(fixture.retainedCopies).hasSize(2 * pausedQueueTestFrameLimit)
            assertThat(fixture.activeRetainedCopies()).hasSize(2 * pausedQueueTestFrameLimit)
            assertThat(fixture.parent.config().isAutoRead).isTrue()

            when (closeMode) {
                "remote reset" -> fixture.resetRemoteChild(children[0])
                "local close" -> children[0].stream.close().join()
                "parent close" -> fixture.closeParent()
            }

            assertThat(children[0].channel.isOpen).isFalse()
            assertThat(firstCopies).allMatch { it.refCnt() == 0 }
            assertThat(children[0].received).isEmpty()
            if (closeMode == "parent close") {
                assertThat(children[1].channel.isOpen).isFalse()
                assertThat(secondCopies).allMatch { it.refCnt() == 0 }
            } else {
                assertThat(children[1].channel.isOpen).isTrue()
                assertThat(secondCopies).allMatch { it.refCnt() == 1 }
                assertThat(fixture.parent.config().isAutoRead).isTrue()
                when (closeMode) {
                    "remote reset" -> fixture.resetRemoteChild(children[1])
                    "local close" -> children[1].stream.close().join()
                    else -> error("Unexpected close mode: $closeMode")
                }
                assertThat(children[1].channel.isOpen).isFalse()
                assertThat(secondCopies).allMatch { it.refCnt() == 0 }
            }

            assertThat(fixture.retainedCopies).allMatch { it.refCnt() == 0 }
            assertThat(fixture.retainedCopies).allMatch { copy ->
                fixture.releasedMessages.any { released -> released === copy }
            }
            assertInputBuffersReleased(fixture)
        }
    }

    @Test
    fun resumingAt64FramesDeliversEveryPayloadInFifoOrder() {
        QueueBoundsFixture().use { fixture ->
            val child = fixture.openRemoteChild()
            child.channel.config().isAutoRead = false
            val expected = (0 until pausedQueueTestFrameLimit).map { it.toByte() }
            expected.forEach { fixture.sendData(child, byteArrayOf(it)) }
            assertRetainedCopyBound(
                fixture,
                expectedFrames = pausedQueueTestFrameLimit,
                expectedBytes = pausedQueueTestFrameLimit.toLong()
            )

            child.channel.config().isAutoRead = true
            fixture.parent.runPendingTasks()

            assertThat(child.received.map { it.single() }).containsExactlyElementsOf(expected)
            assertThat(fixture.retainedCopies).allMatch { it.refCnt() == 0 }
        }
    }

    @Test
    fun rePausingDuringResumeKeepsFifoAndDeliversFinAfterQueuedData() {
        QueueBoundsFixture().use { fixture ->
            val child = fixture.openRemoteChild()
            child.channel.config().isAutoRead = false
            val expected = (0 until 4).map { it.toByte() }
            expected.forEach { fixture.sendData(child, byteArrayOf(it)) }
            fixture.sendFin(child)
            assertThat(child.remoteWriteClosed).isFalse()

            child.onRead = {
                if (child.received.size == 2) child.channel.config().isAutoRead = false
            }
            child.channel.config().isAutoRead = true
            fixture.parent.runPendingTasks()

            assertThat(child.received.map { it.single() }).containsExactly(0.toByte(), 1.toByte())
            assertThat(child.remoteWriteClosed).isFalse()

            child.channel.config().isAutoRead = true
            fixture.parent.runPendingTasks()

            assertThat(child.received.map { it.single() }).containsExactlyElementsOf(expected)
            assertThat(child.events).containsExactly(RemoteWriteClosed)
            assertThat(fixture.retainedCopies).allMatch { it.refCnt() == 0 }
        }
    }

    @Test
    fun resumingChildPreservesAnAlreadyPausedParent() {
        QueueBoundsFixture().use { fixture ->
            val child = fixture.openRemoteChild()
            fixture.parent.config().isAutoRead = false
            child.channel.config().isAutoRead = false
            repeat(pausedQueueTestFrameLimit) { fixture.sendData(child, byteArrayOf(it.toByte())) }

            child.channel.config().isAutoRead = true
            fixture.parent.runPendingTasks()

            assertThat(child.received).hasSize(pausedQueueTestFrameLimit)
            assertThat(fixture.parent.config().isAutoRead).isFalse()
        }
    }

    @Test
    fun overflowReleasesQueuedReadsBeforePendingReplyFailureCallbackResumesChild() {
        QueueBoundsFixture().use { fixture ->
            val child = fixture.openRemoteChild()
            child.channel.config().isAutoRead = false
            repeat(pausedQueueTestFrameLimit) { fixture.sendData(child, byteArrayOf(it.toByte())) }
            val queuedCopies = fixture.retainedCopies.toList()

            val replyPayload = Unpooled.buffer(400, 400).writeZero(400)
            val replyWrite = child.stream.writeAndFlushWithFuture(replyPayload)
            fixture.parent.runPendingTasks()
            assertThat(replyWrite.isDone).isFalse()
            fixture.drainOutbound()

            var callbackRan = false
            var callbackFailure: Throwable? = null
            var copyReferencesAtCallback: List<Int>? = null
            var deliveredAtCallback: List<ByteArray>? = null
            replyWrite.whenComplete { _, failure ->
                callbackRan = true
                callbackFailure = unwrapCompletionFailure(failure)
                copyReferencesAtCallback = queuedCopies.map { it.refCnt() }
                child.channel.config().isAutoRead = true
                fixture.parent.runPendingTasks()
                deliveredAtCallback = child.received.toList()
            }

            fixture.sendData(child, byteArrayOf(0x7f))

            val expectedMessage =
                "Paused child ${child.channel.id().asLongText()} inbound queue exceeded: " +
                    "attempted 65 frames and 65 bytes; limits are 64 frames and 300 bytes; resetting the stream"
            assertThat(callbackRan).isTrue()
            assertThat(checkNotNull(callbackFailure) { "The pending reply write callback had no failure" })
                .isInstanceOf(ConnectionClosedException::class.java)
                .hasMessage(expectedMessage)
            assertThat(copyReferencesAtCallback).containsOnly(0)
            assertThat(deliveredAtCallback).isEmpty()
            assertThat(child.received).isEmpty()
            assertThat(child.channel.isOpen).isFalse()
            assertThat(replyPayload.refCnt()).isZero()
            assertThat(queuedCopies).allMatch { it.refCnt() == 0 }
        }
    }
}

private fun assertRetainedCopyBound(
    fixture: QueueBoundsFixture,
    expectedFrames: Int,
    expectedBytes: Long
) {
    val retained = fixture.activeRetainedCopies()
    assertThat(retained).hasSize(expectedFrames)
    assertThat(retained.sumOf { it.capacity().toLong() }).isEqualTo(expectedBytes)
    assertThat(retained.map { it.capacity() }).containsOnly(1)
    assertThat(retained.all { it.refCnt() == 1 }).isTrue()
}

private fun assertInputBuffersReleased(fixture: QueueBoundsFixture) {
    assertThat(fixture.inputBuffers).allMatch { it.refCnt() == 0 }
}

private fun appendYamuxFrame(
    output: ByteBuf,
    streamId: Long,
    flags: Set<YamuxFlag>,
    payload: ByteArray
) {
    output.writeByte(0)
        .writeByte(YamuxType.DATA.intValue)
        .writeShort(flags.fold(0) { result, flag -> result or flag.intFlag })
        .writeInt(streamId.toInt())
        .writeInt(payload.size)
        .writeBytes(payload)
}

private fun unwrapCompletionFailure(failure: Throwable?): Throwable? {
    var cause = failure ?: return null
    while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) {
        cause = cause.cause!!
    }
    return cause
}

private data class CapturedYamuxFrame(
    val streamId: Long,
    val type: YamuxType,
    val flags: Set<YamuxFlag>
)

private class OutboundFrameCapture : ChannelOutboundHandlerAdapter() {
    val frames = mutableListOf<CapturedYamuxFrame>()

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is YamuxFrame) {
            frames += CapturedYamuxFrame(msg.id.id, msg.type, msg.flags.toSet())
        }
        ctx.write(msg, promise)
    }
}

private class QueueBoundsChild(val stream: Stream) : ChannelInboundHandlerAdapter() {
    lateinit var channel: Channel
        private set
    val received = mutableListOf<ByteArray>()
    val exceptions = mutableListOf<Throwable>()
    val events = mutableListOf<Any>()
    var onRead: (() -> Unit)? = null

    val streamId: Long
        get() = (channel.id() as YamuxId).id

    val remoteWriteClosed: Boolean
        get() = events.contains(RemoteWriteClosed)

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        channel = ctx.channel()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val data = msg as ByteBuf
        try {
            val bytes = ByteArray(data.readableBytes())
            data.readBytes(bytes)
            received += bytes
        } finally {
            ReferenceCountUtil.release(msg)
        }
        onRead?.invoke()
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        events += evt
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        exceptions += cause
    }
}

private class QueueBoundsFixture : AutoCloseable {
    val retainedCopies = mutableListOf<ByteBuf>()
    val releasedMessages = mutableListOf<ByteBuf>()
    val inputBuffers = mutableListOf<ByteBuf>()
    private val outboundCapture = OutboundFrameCapture()
    val children = mutableListOf<QueueBoundsChild>()
    private var nextRemoteStreamId = 2L

    private val inboundStreamHandler = StreamHandler<Unit> { stream ->
        val child = QueueBoundsChild(stream)
        children += child
        stream.pushHandler(child)
        CompletableFuture.completedFuture(Unit)
    }

    val muxHandler = object : YamuxHandler(
        MultistreamProtocolV1,
        pausedQueueTestFrameSize,
        null,
        inboundStreamHandler,
        true,
        1024 * 1024,
        DEFAULT_ACK_BACKLOG_LIMIT,
        pausedQueueTestWindowSize
    ) {
        override fun preparePendingChildRead(data: ByteBuf, retainedBytes: Long): ByteBuf {
            val copy = super.preparePendingChildRead(data, retainedBytes)
            retainedCopies += copy
            return copy
        }

        override fun releaseMessage(msg: ByteBuf) {
            releasedMessages += msg
            super.releaseMessage(msg)
        }
    }

    val parent = TestChannel(
        "yamux-paused-queue-bounds",
        true,
        YamuxFrameCodec(pausedQueueTestFrameSize),
        outboundCapture,
        muxHandler
    )

    val outboundFrames: List<CapturedYamuxFrame>
        get() = outboundCapture.frames

    fun openRemoteChild(): QueueBoundsChild {
        val streamId = nextRemoteStreamId
        nextRemoteStreamId += 2
        parent.writeInbound(
            YamuxFrame(
                YamuxId(parent.id(), streamId),
                YamuxType.DATA,
                YamuxFlag.SYN.asSet,
                0
            )
        )
        parent.runPendingTasks()
        val child = children.single { it.streamId == streamId }
        drainOutbound()
        return child
    }

    fun sendData(child: QueueBoundsChild, payload: ByteArray) {
        val wire = Unpooled.buffer(12 + payload.size, 12 + payload.size)
        appendYamuxFrame(wire, child.streamId, YamuxFlag.NONE, payload)
        writeWire(wire)
    }

    fun sendFin(child: QueueBoundsChild) {
        val wire = Unpooled.buffer(12, 12)
        appendYamuxFrame(wire, child.streamId, YamuxFlag.FIN.asSet, byteArrayOf())
        writeWire(wire)
    }

    fun resetRemoteChild(child: QueueBoundsChild) {
        val reset = YamuxFrame(
            YamuxId(parent.id(), child.streamId),
            YamuxType.DATA,
            YamuxFlag.RST.asSet,
            0
        )
        parent.writeInbound(reset)
        parent.runPendingTasks()
    }

    fun writeWire(wire: ByteBuf) {
        inputBuffers += wire
        parent.writeInbound(wire)
    }

    fun activeRetainedCopies(): List<ByteBuf> = retainedCopies.filter { it.refCnt() > 0 }

    fun drainOutbound() {
        while (true) {
            val message = parent.readOutbound<Any>() ?: return
            ReferenceCountUtil.release(message)
        }
    }

    fun closeParent() {
        if (parent.isOpen) parent.close().syncUninterruptibly()
        parent.runPendingTasks()
    }

    override fun close() {
        closeParent()
        parent.finishAndReleaseAll()
    }
}
