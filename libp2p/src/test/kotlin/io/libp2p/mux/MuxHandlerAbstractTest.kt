package io.libp2p.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.StreamHandler
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.getX
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.mux.MuxHandlerAbstractTest.TestEventHandler
import io.libp2p.tools.TestChannel
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.WriteBufferWaterMark
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Index
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Created by Anton Nashatyrev on 09.07.2019.
 */
abstract class MuxHandlerAbstractTest {
    val childHandlers = mutableListOf<TestHandler>()
    lateinit var multistreamHandler: MuxHandler
    lateinit var ech: TestChannel
    val parentChannelId get() = ech.id()

    val allocatedBufs = mutableListOf<ByteBuf>()
    val activeEventHandlers = mutableListOf<TestEventHandler>()
    val isLocalConnectionInitiator = true

    abstract val maxFrameDataLength: Int
    abstract fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler

    abstract val localMuxIdGenerator: Iterator<Long>
    abstract val remoteMuxIdGenerator: Iterator<Long>

    fun createTestStreamHandler(): StreamHandler<TestHandler> =
        StreamHandler { stream ->
            val handler = TestHandler()
            stream.pushHandler(
                nettyInitializer {
                    it.addLastLocal(handler)
                }
            )
            CompletableFuture.completedFuture(handler)
        }

    fun <T> StreamHandler<T>.onNewStream(block: (T) -> Unit): StreamHandler<T> =
        StreamHandler { stream ->
            this.handleStream(stream)
                .thenApply {
                    block(it)
                    it
                }
        }

    @BeforeEach
    fun startMultiplexor() {
        val streamHandler = createTestStreamHandler()
            .onNewStream {
                childHandlers += it
            }
        multistreamHandler = createMuxHandler(streamHandler)

        ech = TestChannel("test", isLocalConnectionInitiator, LoggingHandler(LogLevel.ERROR), multistreamHandler)
    }

    @AfterEach
    open fun cleanUpAndCheck() {
        try {
            childHandlers.forEach { assertThat(it.exceptions).isEmpty() }
            allocatedBufs.forEach { assertThat(it.refCnt()).isEqualTo(1) }
        } finally {
            try {
                ech.close().sync()
            } finally {
                try {
                    ech.finishAndReleaseAll()
                } finally {
                    allocatedBufs.forEach { while (it.refCnt() > 0) it.release() }
                    allocatedBufs.clear()
                    childHandlers.clear()
                }
            }
        }
    }

    data class AbstractTestMuxFrame(
        val streamId: Long,
        val flag: Flag,
        val data: String = ""
    ) {
        enum class Flag { Open, Data, Close, Reset }
    }

    abstract fun writeFrame(frame: AbstractTestMuxFrame)
    abstract fun readFrame(): AbstractTestMuxFrame?
    fun readFrameOrThrow() = readFrame() ?: throw AssertionError("No outbound frames")
    fun openStreamRemote(id: Long) = writeFrame(AbstractTestMuxFrame(id, Open))
    fun openStreamRemote(): Long {
        val id = remoteMuxIdGenerator.next()
        openStreamRemote(id)
        return id
    }
    fun writeStream(id: Long, msg: String) = writeFrame(AbstractTestMuxFrame(id, Data, msg))
    fun closeStream(id: Long) = writeFrame(AbstractTestMuxFrame(id, Close))
    fun resetStream(id: Long) = writeFrame(AbstractTestMuxFrame(id, Reset))

    fun openStreamLocal(): TestHandler {
        val handlerFut = multistreamHandler.createStream(createTestStreamHandler()).controller
        ech.runPendingTasks()
        return handlerFut.get()
    }

    @Test
    fun childChannelKeepsItsConfiguration() {
        openStreamRemote()
        val channel = childHandlers.single().ctx.channel()
        val config = channel.config()
        config.isAutoRead = false
        config.writeBufferWaterMark = WriteBufferWaterMark(1024, 2048)

        assertSame(config, channel.config())
        assertFalse(channel.config().isAutoRead)
        assertEquals(1024, channel.config().writeBufferWaterMark.low())
        assertEquals(2048, channel.config().writeBufferWaterMark.high())
    }

    @Test
    fun childChannelPausesInboundDeliveryUntilAutoReadResumes() {
        val streamId = openStreamRemote()
        val handler = childHandlers.single()
        handler.ctx.channel().config().isAutoRead = false

        writeStream(streamId, "22")
        writeStream(streamId, "44")
        assertTrue(handler.inboundMessages.isEmpty())
        assertEquals(0, handler.readCompleteEventCount)

        handler.ctx.channel().config().isAutoRead = true
        ech.runPendingTasks()
        assertEquals(listOf("22", "44"), handler.inboundMessages)
        assertEquals(1, handler.readCompleteEventCount)
    }

    @Test
    fun pausedChildStopsParentReadsUntilEveryPausedChildResumes() {
        openStreamRemote()
        openStreamRemote()
        val first = childHandlers[0].ctx.channel()
        val second = childHandlers[1].ctx.channel()

        first.config().isAutoRead = false
        assertFalse(ech.config().isAutoRead)
        second.config().isAutoRead = false
        first.config().isAutoRead = true
        ech.runPendingTasks()
        assertFalse(ech.config().isAutoRead)

        second.config().isAutoRead = true
        ech.runPendingTasks()
        assertTrue(ech.config().isAutoRead)
    }

    @Test
    fun pausingAndResumingChildPreservesAnAlreadyPausedParent() {
        val streamId = openStreamRemote()
        val child = childHandlers.single().ctx.channel()
        ech.config().isAutoRead = false
        child.config().isAutoRead = false
        writeStream(streamId, "22")
        child.config().isAutoRead = true
        ech.runPendingTasks()

        assertEquals(listOf("22"), childHandlers.single().inboundMessages)
        assertFalse(ech.config().isAutoRead)
    }

    @Test
    fun closingOneOfTwoPausedChildrenKeepsParentPaused() {
        val firstId = openStreamRemote()
        openStreamRemote()
        val first = childHandlers[0].ctx.channel()
        val second = childHandlers[1].ctx.channel()
        first.config().isAutoRead = false
        second.config().isAutoRead = false

        resetStream(firstId)
        assertFalse(ech.config().isAutoRead)
        second.config().isAutoRead = true
        ech.runPendingTasks()
        assertTrue(ech.config().isAutoRead)
    }

    @Test
    fun concurrentConfigSettersLeaveParentAndChildInAgreement() {
        val streamId = openStreamRemote()
        val child = childHandlers.single().ctx.channel()
        val config = child.config()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val writes = List(2) {
                workers.submit {
                    start.await()
                    repeat(100) { index ->
                        assertSame(config, child.config())
                        child.config().isAutoRead = index % 2 == 0
                    }
                }
            }
            start.countDown()
            writes.forEach { it.get(5, TimeUnit.SECONDS) }
            config.isAutoRead = false
            ech.runPendingTasks()
            assertFalse(ech.config().isAutoRead)
            writeStream(streamId, "22")
            assertTrue(childHandlers.single().inboundMessages.isEmpty())

            config.isAutoRead = true
            ech.runPendingTasks()
            assertTrue(ech.config().isAutoRead)
            assertEquals(listOf("22"), childHandlers.single().inboundMessages)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun crossThreadPauseAllowsOnlyTheAlreadyInFlightPayload() {
        val streamId = openStreamRemote()
        val handler = childHandlers.single()
        val child = handler.ctx.channel()
        val enteredDelivery = CountDownLatch(1)
        val continueDelivery = CountDownLatch(1)
        child.pipeline().addBefore(
            handler.ctx.name(),
            "in-flight-pause-gate",
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    enteredDelivery.countDown()
                    assertTrue(continueDelivery.await(5, TimeUnit.SECONDS))
                    ctx.fireChannelRead(msg)
                }
            }
        )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val firstWrite = worker.submit { writeStream(streamId, "22") }
            assertTrue(enteredDelivery.await(5, TimeUnit.SECONDS))
            child.config().isAutoRead = false
            continueDelivery.countDown()
            firstWrite.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("22"), handler.inboundMessages)

            writeStream(streamId, "44")
            assertEquals(listOf("22"), handler.inboundMessages)
            child.config().isAutoRead = true
            ech.runPendingTasks()
            assertEquals(listOf("22", "44"), handler.inboundMessages)
        } finally {
            continueDelivery.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun closingPausedChildReleasesUndeliveredPayloadAndRestoresParentReads() {
        val streamId = openStreamRemote()
        val handler = childHandlers.single()
        val child = handler.ctx.channel()
        val config = child.config()
        config.isAutoRead = false
        writeStream(streamId, "22")
        val payload = allocatedBufs.last()

        assertTrue(handler.inboundMessages.isEmpty())
        resetStream(streamId)
        ech.runPendingTasks()
        assertTrue(handler.inboundMessages.isEmpty())
        assertEquals(1, payload.refCnt())
        assertSame(config, child.config())
        assertTrue(ech.config().isAutoRead)
    }

    @Test
    fun closingParentReleasesPausedChildPayload() {
        val streamId = openStreamRemote()
        val handler = childHandlers.single()
        val child = handler.ctx.channel()
        child.config().isAutoRead = false
        writeStream(streamId, "22")
        val payload = allocatedBufs.last()

        ech.close().sync()

        assertTrue(child.closeFuture().isDone)
        assertTrue(handler.inboundMessages.isEmpty())
        assertEquals(1, payload.refCnt())
    }

    @Test
    fun pausingAgainDuringQueuedDeliveryKeepsRemainingPayloadInOrder() {
        val streamId = openStreamRemote()
        val handler = childHandlers.single()
        val child = handler.ctx.channel()
        val config = child.config()
        child.config().isAutoRead = false
        writeStream(streamId, "22")
        writeStream(streamId, "44")
        writeStream(streamId, "66")
        handler.onRead = {
            assertSame(config, child.config())
            if (handler.inboundMessages.size == 1) child.config().isAutoRead = false
        }

        child.config().isAutoRead = true
        ech.runPendingTasks()
        assertEquals(listOf("22"), handler.inboundMessages)
        assertFalse(ech.config().isAutoRead)

        child.config().isAutoRead = true
        ech.runPendingTasks()
        assertEquals(listOf("22", "44", "66"), handler.inboundMessages)
    }

    @Test
    fun remoteEndWaitsForPausedDataEvenAfterLocalDisconnect() {
        val streamId = openStreamRemote()
        val handler = childHandlers.single()
        val child = handler.ctx.channel()
        child.config().isAutoRead = false
        writeStream(streamId, "22")
        val payload = allocatedBufs.last()
        closeStream(streamId)
        handler.ctx.disconnect().sync()

        assertTrue(handler.inboundMessages.isEmpty())
        assertTrue(handler.userEvents.isEmpty())
        assertFalse(child.closeFuture().isDone)
        handler.onRead = { assertTrue(handler.userEvents.isEmpty()) }

        child.config().isAutoRead = true
        ech.runPendingTasks()
        assertEquals(listOf("22"), handler.inboundMessages)
        assertEquals(listOf(RemoteWriteClosed), handler.userEvents)
        assertTrue(child.closeFuture().isDone)
        assertEquals(1, payload.refCnt())
    }

    @Test
    fun remoteDataAfterDeferredEndIsRejected() {
        val streamId = openStreamRemote()
        val handler = childHandlers.single()
        val child = handler.ctx.channel()
        child.config().isAutoRead = false
        writeStream(streamId, "22")
        closeStream(streamId)

        assertThrows(Libp2pException::class.java) { writeStream(streamId, "44") }
        child.config().isAutoRead = true
        ech.runPendingTasks()
        assertEquals(listOf("22"), handler.inboundMessages)
        assertEquals(listOf(RemoteWriteClosed), handler.userEvents)
    }

    protected fun allocateBuf(): ByteBuf {
        val buf = Unpooled.buffer()
        buf.retain() // ref counter to 2 to check that exactly 1 ref remains at the end
        allocatedBufs += buf
        return buf
    }

    protected fun allocateMessage(hexBytes: String) = hexBytes.fromHex().toByteBuf(allocateBuf())

    fun assertHandlerCount(count: Int) = assertEquals(count, childHandlers.size)
    fun assertLastMessage(handler: Int, msgCount: Int, msg: String) {
        val messages = childHandlers[handler].inboundMessages
        assertEquals(msgCount, messages.size)
        assertEquals(msg, messages.last())
    }

    @Test
    fun singleStream() {
        val id1 = openStreamRemote()
        assertHandlerCount(1)
        assertTrue(childHandlers[0].isActivated)

        writeStream(id1, "22")
        assertHandlerCount(1)
        assertEquals(1, childHandlers[0].inboundMessages.size)
        assertEquals("22", childHandlers[0].inboundMessages.last())

        writeStream(id1, "44")
        assertHandlerCount(1)
        assertEquals(2, childHandlers[0].inboundMessages.size)
        assertEquals("44", childHandlers[0].inboundMessages.last())

        writeStream(id1, "66")
        assertHandlerCount(1)
        assertEquals(3, childHandlers[0].inboundMessages.size)
        assertEquals("66", childHandlers[0].inboundMessages.last())

        assertFalse(childHandlers[0].isInactivated)
    }

    @Test
    fun `test that readComplete event is fired to child channel`() {
        val id1 = openStreamRemote()

        assertThat(childHandlers[0].readCompleteEventCount).isZero()

        writeStream(id1, "22")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(1)

        writeStream(id1, "23")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(2)
    }

    @Test
    fun `test that readComplete event is fired to reading channels only`() {
        val id1 = openStreamRemote()
        val id2 = openStreamRemote()

        assertThat(childHandlers[0].readCompleteEventCount).isZero()
        assertThat(childHandlers[1].readCompleteEventCount).isZero()

        writeStream(id1, "22")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(1)
        assertThat(childHandlers[1].readCompleteEventCount).isEqualTo(0)

        writeStream(id2, "23")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(1)
        assertThat(childHandlers[1].readCompleteEventCount).isEqualTo(1)
    }

    @Test
    fun twoStreamsInterleaved() {
        val id1 = openStreamRemote()
        writeStream(id1, "22")

        assertHandlerCount(1)
        assertLastMessage(0, 1, "22")

        writeStream(id1, "23")
        assertHandlerCount(1)
        assertLastMessage(0, 2, "23")

        val id2 = openStreamRemote()
        writeStream(id2, "33")
        assertHandlerCount(2)
        assertLastMessage(1, 1, "33")

        writeStream(id1, "24")
        assertHandlerCount(2)
        assertLastMessage(0, 3, "24")

        writeStream(id2, "34")
        assertHandlerCount(2)
        assertLastMessage(1, 2, "34")

        assertFalse(childHandlers[0].isInactivated)
        assertFalse(childHandlers[1].isInactivated)
    }

    @Test
    fun twoStreamsSequential() {
        val id1 = openStreamRemote()
        writeStream(id1, "22")

        assertHandlerCount(1)
        assertLastMessage(0, 1, "22")

        writeStream(id1, "23")
        assertHandlerCount(1)
        assertLastMessage(0, 2, "23")

        writeStream(id1, "24")
        assertHandlerCount(1)
        assertLastMessage(0, 3, "24")

        writeStream(id1, "25")
        assertHandlerCount(1)
        assertLastMessage(0, 4, "25")

        assertFalse(childHandlers[0].isInactivated)
        resetStream(id1)
        assertTrue(childHandlers[0].isHandlerRemoved)

        val id2 = openStreamRemote()
        writeStream(id2, "33")
        assertHandlerCount(2)
        assertLastMessage(1, 1, "33")

        writeStream(id2, "34")
        assertHandlerCount(2)
        assertLastMessage(1, 2, "34")

        assertFalse(childHandlers[1].isInactivated)
        resetStream(id2)
        assertTrue(childHandlers[1].isHandlerRemoved)
    }

    @Test
    fun streamIsReset() {
        val id1 = openStreamRemote()
        assertFalse(childHandlers[0].ctx.channel().closeFuture().isDone)
        assertFalse(childHandlers[0].isInactivated)

        resetStream(id1)
        assertTrue(childHandlers[0].ctx.channel().closeFuture().isDone)
        assertTrue(childHandlers[0].isHandlerRemoved)
    }

    @Test
    fun streamIsResetWhenChannelIsClosed() {
        openStreamRemote()
        assertFalse(childHandlers[0].ctx.channel().closeFuture().isDone)

        ech.close().await()

        assertTrue(childHandlers[0].ctx.channel().closeFuture().isDone)
        assertTrue(childHandlers[0].isHandlerRemoved)
    }

    @Test
    fun cantReceiveOnResetStream() {
        val id1 = openStreamRemote()
        resetStream(id1)

        assertThrows(Libp2pException::class.java) {
            writeStream(id1, "35")
        }
        assertTrue(childHandlers[0].isHandlerRemoved)
    }

    @Test
    fun cantReceiveOnClosedStream() {
        val id1 = openStreamRemote()
        closeStream(id1)

        assertThrows(Libp2pException::class.java) {
            writeStream(id1, "35")
        }
        assertFalse(childHandlers[0].isInactivated)
    }

    @Test
    fun cantReceiveOnNonExistentStream() {
        assertThrows(Libp2pException::class.java) {
            writeStream(92, "35")
        }
        assertHandlerCount(0)
    }

    @Test
    @SuppressWarnings("SwallowedException")
    fun `resetting non existing stream doesnt close connection`() {
        try {
            resetStream(99)
        } catch (e: UnknownStreamIdMuxerException) {
            // Muxer is free to either throw an exception or just ignore
        }
        assertHandlerCount(0)
        assertThat(ech.isOpen).isTrue()
    }

    @Test
    fun cantOpenStreamOnClosedChannel() {
        ech.close().await()

        val staleStream =
            multistreamHandler.createStream {
                println("This shouldn't be displayed: parent stream is closed")
                CompletableFuture.completedFuture(Unit)
            }

        assertThrows(ConnectionClosedException::class.java) { staleStream.stream.getX(3.0) }
        assertHandlerCount(0)
    }

    @Test
    fun `opening a stream with existing id causes connection close`() {
        val id1 = openStreamRemote()
        assertThrows(Libp2pException::class.java) {
            openStreamRemote(id1)
        }

        assertThat(ech.isOpen).isFalse()
    }

    @Test
    fun `local create and after local disconnect should still read`() {
        val handler = openStreamLocal()
        handler.ctx.writeAndFlush(allocateMessage("1984"))
        handler.ctx.disconnect().sync()

        val openFrame = readFrameOrThrow()
        assertThat(openFrame.flag).isEqualTo(Open)

        val dataFrame = readFrameOrThrow()
        assertThat(dataFrame.flag).isEqualTo(Data)
        assertThat(dataFrame.streamId).isEqualTo(openFrame.streamId)

        val closeFrame = readFrameOrThrow()
        assertThat(closeFrame.flag).isEqualTo(Close)

        assertThat(readFrame()).isNull()
        assertThat(handler.isInactivated).isTrue()
        assertThat(handler.isUnregistered).isFalse()
        assertThat(handler.inboundMessages).isEmpty()

        writeStream(dataFrame.streamId, "1122")
        assertThat(handler.inboundMessages).isNotEmpty
    }

    @Test
    fun `local create and after remote disconnect should still write`() {
        val handler = openStreamLocal()

        val openFrame = readFrameOrThrow()
        assertThat(openFrame.flag).isEqualTo(Open)
        assertThat(readFrame()).isNull()

        closeStream(openFrame.streamId)

        assertThat(handler.isInactivated).isFalse()
        assertThat(handler.isUnregistered).isFalse()
        assertThat(handler.userEvents).containsExactly(RemoteWriteClosed)

        handler.ctx.writeAndFlush(allocateMessage("1984"))

        val readFrame = readFrameOrThrow()
        assertThat(readFrame.flag).isEqualTo(Data)
        assertThat(readFrame.data).isEqualTo("1984")
        assertThat(readFrame()).isNull()
    }

    @Test
    fun `test remote and local disconnect closes stream`() {
        val handler = openStreamLocal()
        handler.ctx.disconnect().sync()

        readFrameOrThrow()
        val closeFrame = readFrameOrThrow()
        assertThat(closeFrame.flag).isEqualTo(Close)

        assertThat(handler.isInactivated).isTrue()
        assertThat(handler.isUnregistered).isFalse()

        closeStream(closeFrame.streamId)

        assertThat(handler.isHandlerRemoved).isTrue()
    }

    @Test
    fun `test large message is split onto slices`() {
        val handler = openStreamLocal()
        readFrameOrThrow()

        val largeMessage = "42".repeat(maxFrameDataLength - 1) + "4344"
        handler.ctx.writeAndFlush(allocateMessage(largeMessage))

        val dataFrame1 = readFrameOrThrow()
        assertThat(dataFrame1.data.fromHex())
            .hasSize(maxFrameDataLength)
            .contains(0x42, Index.atIndex(0))
            .contains(0x42, Index.atIndex(maxFrameDataLength - 2))
            .contains(0x43, Index.atIndex(maxFrameDataLength - 1))

        val dataFrame2 = readFrameOrThrow()
        assertThat(dataFrame2.data.fromHex())
            .hasSize(1)
            .contains(0x44, Index.atIndex(0))

        assertThat(readFrame()).isNull()
    }

    @Test
    fun `should throw when writing to locally closed stream`() {
        val handler = openStreamLocal()
        handler.ctx.disconnect()

        assertThrows(Exception::class.java) {
            handler.ctx.writeAndFlush(allocateMessage("42")).sync()
        }
    }

    @Test
    fun `write with localDisconnected should fail promise without throwing from doWrite`() {
        val handler = openStreamLocal()
        readFrameOrThrow()

        // Simulate the state between localDisconnected=true and deactivate() in doDisconnect(),
        // which is when a queued WriteTask can reach doWrite with localDisconnected=true while
        // the channel is still active (flush0 would take the "not-yet-connected" path otherwise).
        @Suppress("UNCHECKED_CAST")
        (handler.ctx.channel() as MuxChannel<ByteBuf>).localDisconnected = true

        val writeFuture = handler.ctx.writeAndFlush(allocateMessage("42"))
        ech.runPendingTasks()

        assertTrue(writeFuture.isDone)
        assertThrows(ConnectionClosedException::class.java) {
            writeFuture.sync()
        }
    }

    @Test
    fun `should throw when writing to reset stream`() {
        val handler = openStreamLocal()
        handler.ctx.close()

        assertThrows(Exception::class.java) {
            handler.ctx.writeAndFlush(allocateMessage("42")).sync()
        }
    }

    @Test
    fun `should throw when writing to closed connection`() {
        val handler = openStreamLocal()
        ech.close().sync()

        assertThrows(Exception::class.java) {
            handler.ctx.writeAndFlush(allocateMessage("42")).sync()
        }
    }

    @Test
    fun `test writing to remotely open stream upon activation`() {
        activeEventHandlers += TestEventHandler {
            val writePromise = it.ctx.writeAndFlush(allocateMessage("42"))
            writePromise.sync()
        }
        val id1 = openStreamRemote()

        val dataFrame = readFrameOrThrow()
        assertThat(dataFrame.streamId).isEqualTo(id1)
        assertThat(dataFrame.data).isEqualTo("42")
    }

    fun interface TestEventHandler {
        fun handle(testHandler: TestHandler)
    }

    inner class TestHandler : ChannelInboundHandlerAdapter() {
        val inboundMessages = mutableListOf<String>()
        var onRead: (() -> Unit)? = null
        lateinit var ctx: ChannelHandlerContext
        var readCompleteEventCount = 0

        val exceptions = mutableListOf<Throwable>()
        val userEvents = mutableListOf<Any>()
        var isHandlerAdded = false
        var isRegistered = false
        var isActivated = false
        var isInactivated = false
        var isUnregistered = false
        var isHandlerRemoved = false

        init {
            println("New child channel created")
        }

        override fun handlerAdded(ctx: ChannelHandlerContext) {
            assertFalse(isHandlerAdded)
            isHandlerAdded = true
            println("MuxHandlerAbstractTest.handlerAdded")
            this.ctx = ctx
        }

        override fun channelRegistered(ctx: ChannelHandlerContext?) {
            assertTrue(isHandlerAdded)
            assertFalse(isRegistered)
            isRegistered = true
            println("MuxHandlerAbstractTest.channelRegistered")
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            assertTrue(isRegistered)
            assertFalse(isActivated)
            isActivated = true
            println("MuxHandlerAbstractTest.channelActive")
            activeEventHandlers.forEach { it.handle(this) }
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            assertTrue(isActivated)
            println("MuxHandlerAbstractTest.channelRead")
            msg as ByteBuf
            inboundMessages += msg.readAllBytesAndRelease().toHex()
            onRead?.invoke()
        }

        override fun channelReadComplete(ctx: ChannelHandlerContext?) {
            readCompleteEventCount++
            println("MuxHandlerAbstractTest.channelReadComplete")
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            userEvents += evt
            println("MuxHandlerAbstractTest.userEventTriggered: $evt")
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            exceptions += cause
            println("MuxHandlerAbstractTest.exceptionCaught")
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            assertTrue(isActivated)
            assertFalse(isInactivated)
            isInactivated = true
            println("MuxHandlerAbstractTest.channelInactive")
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            assertTrue(isInactivated)
            assertFalse(isUnregistered)
            isUnregistered = true
            println("MuxHandlerAbstractTest.channelUnregistered")
        }

        override fun handlerRemoved(ctx: ChannelHandlerContext?) {
            assertTrue(isUnregistered)
            assertFalse(isHandlerRemoved)
            isHandlerRemoved = true
            println("MuxHandlerAbstractTest.handlerRemoved")
        }
    }

    companion object {
        fun ByteArray.toByteBuf(buf: ByteBuf): ByteBuf = buf.writeBytes(this)
    }
}
