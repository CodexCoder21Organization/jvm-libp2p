package io.libp2p.mux.yamux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.WRITE_FAILURE
import io.libp2p.etc.types.sliceMaxSize
import io.libp2p.etc.types.writeOnce
import io.libp2p.etc.util.netty.ByteBufQueue
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.*
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.PromiseCombiner
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.properties.Delegates

const val DEFAULT_MAX_BUFFERED_CONNECTION_WRITES = 10 * 1024 * 1024 // 10 MiB
const val DEFAULT_ACK_BACKLOG_LIMIT = 256

const val INITIAL_WINDOW_SIZE = 256 * 1024
private const val YAMUX_HEADER_BYTES = 12L

open class YamuxHandler(
    override val multistreamProtocol: MultistreamProtocol,
    override val maxFrameDataLength: Int,
    ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>,
    private val connectionInitiator: Boolean,
    private val maxBufferedConnectionWrites: Int,
    private val ackBacklogLimit: Int,
    private val initialWindowSize: Int = INITIAL_WINDOW_SIZE
) : MuxHandler(ready, inboundStreamHandler) {

    private inner class YamuxStreamHandler(
        val id: MuxId,
        val outbound: Boolean
    ) {
        val acknowledged = AtomicBoolean(false)
        val sendWindowSize = AtomicInteger(initialWindowSize)
        val receiveWindowSize = AtomicInteger(initialWindowSize)
        val sendBuffer = ByteBufQueue()
        var closedForWriting by Delegates.writeOnce(false)
        private var pendingWrite: PendingWrite? = null
        private var resetCause: Throwable? = null
        private var resetSent = false

        private inner class PendingWrite(
            private val context: ChannelHandlerContext,
            val child: MuxChannel<ByteBuf>
        ) {
            val promise: ChannelPromise = context.newPromise()
            private val frameFutures = mutableListOf<ChannelFuture>()

            fun add(frameFuture: ChannelFuture) {
                frameFutures += frameFuture
                frameFuture.addListener {
                    if (!it.isSuccess) {
                        val cause = it.cause()
                            ?: ConnectionClosedException("Yamux frame write failed without a cause: $id")
                        onFrameWriteFailure(this, cause)
                    }
                }
            }

            fun finish() {
                if (frameFutures.isEmpty()) {
                    promise.trySuccess()
                } else {
                    val combiner = PromiseCombiner(context.executor())
                    frameFutures.forEach(combiner::add)
                    frameFutures.clear()
                    combiner.finish(promise)
                }
            }

            fun fail(cause: Throwable): Boolean {
                frameFutures.clear()
                return promise.tryFailure(cause)
            }
        }

        private fun onFrameWriteFailure(write: PendingWrite, cause: Throwable) {
            if (!write.fail(cause)) return

            if (pendingWrite === write) {
                pendingWrite = null
            }
            try {
                onLocalClose(cause)
            } catch (resetFailure: Throwable) {
                if (resetFailure !== cause) cause.addSuppressed(resetFailure)
            } finally {
                write.child.close()
            }
        }

        fun dispose(cause: Throwable = ConnectionClosedException("Yamux connection closed with buffered data: $id")) {
            sendBuffer.dispose()
            pendingWrite?.fail(cause)
            pendingWrite = null
        }

        fun handleFrameRead(msg: YamuxFrame) {
            handleFlags(msg)
            when (msg.type) {
                YamuxType.DATA -> handleDataRead(msg)
                YamuxType.WINDOW_UPDATE -> handleWindowUpdate(msg)
                else -> {
                    /* ignore */
                }
            }
        }

        private fun handleDataRead(msg: YamuxFrame) {
            val size = msg.length.toInt()
            if (size == 0) {
                return
            }
            acknowledgeInboundStreamIfNeeded()
            val newWindow = receiveWindowSize.addAndGet(-size)
            // send a window update frame once half of the window is depleted
            if (newWindow < initialWindowSize / 2) {
                val delta = initialWindowSize - newWindow
                receiveWindowSize.addAndGet(delta)
                try {
                    writeAndFlushFrame(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, YamuxFlag.NONE, delta.toLong()))
                } catch (cause: Throwable) {
                    ReferenceCountUtil.release(msg.data)
                    throw cause
                }
            }
            childRead(msg.id, msg.data!!)
        }

        private fun handleWindowUpdate(msg: YamuxFrame) {
            val delta = msg.length.toInt()
            sendWindowSize.addAndGet(delta)
            // try to send any buffered messages after the window update
            drainBufferAndMaybeClose()
        }

        private fun handleFlags(msg: YamuxFrame) {
            when {
                YamuxFlag.SYN in msg.flags -> {
                    // ACK the new stream
                    writeAndFlushFrame(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, YamuxFlag.ACK.asSet, 0))
                }

                YamuxFlag.ACK in msg.flags -> {
                    acknowledgeOutboundStreamIfNeeded()
                }

                YamuxFlag.FIN in msg.flags -> onRemoteDisconnect(msg.id)
                YamuxFlag.RST in msg.flags -> onRemoteClose(msg.id)
            }
        }

        private fun acknowledgeInboundStreamIfNeeded() {
            if (!outbound) {
                acknowledged.set(true)
            }
        }

        private fun acknowledgeOutboundStreamIfNeeded() {
            if (outbound) {
                acknowledged.set(true)
            }
        }

        private fun fillBuffer(data: ByteBuf) {
            sendBuffer.push(data)
            val totalBufferedWrites = calculateTotalBufferedWrites()
            if (totalBufferedWrites > maxBufferedConnectionWrites + sendWindowSize.get()) {
                val cause = WriteBufferOverflowMuxerException(
                    "Overflowed send buffer ($totalBufferedWrites/$maxBufferedConnectionWrites). Last stream attempting to write: $id"
                )
                onLocalClose(cause)
                throw cause
            }
        }

        private fun drainBufferAndMaybeClose(): ChannelFuture {
            val ctx = getChannelHandlerContext()
            val applicationWrite = pendingWrite
            val aggregate = applicationWrite?.promise ?: ctx.newPromise()
            val localCombiner = if (applicationWrite == null) PromiseCombiner(ctx.executor()) else null
            var localFrameCount = 0
            fun add(frameFuture: ChannelFuture) {
                if (applicationWrite == null) {
                    localCombiner!!.add(frameFuture)
                    localFrameCount++
                } else {
                    applicationWrite.add(frameFuture)
                }
            }

            val maxSendLength = max(0, sendWindowSize.get())
            val data = sendBuffer.take(maxSendLength)
            sendWindowSize.addAndGet(-data.readableBytes())
            val slices = data.sliceMaxSize(maxFrameDataLength)
            var nextSliceIndex = 0
            try {
                slices.forEachIndexed { index, slicedData ->
                    nextSliceIndex = index + 1
                    val length = slicedData.readableBytes()
                    add(
                        writeAndFlushFrame(
                            YamuxFrame(id, YamuxType.DATA, YamuxFlag.NONE, length.toLong(), slicedData)
                        )
                    )
                    if (applicationWrite?.promise?.isDone == true && !applicationWrite.promise.isSuccess) {
                        for (remainingIndex in nextSliceIndex until slices.size) {
                            ReferenceCountUtil.release(slices[remainingIndex])
                        }
                        return aggregate
                    }
                }
            } catch (cause: Throwable) {
                for (index in nextSliceIndex until slices.size) {
                    ReferenceCountUtil.release(slices[index])
                }
                throw cause
            }

            if (closedForWriting && sendBuffer.readableBytes() == 0) {
                add(writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, YamuxFlag.FIN.asSet, 0)))
            }

            if (applicationWrite != null) {
                if (sendBuffer.readableBytes() == 0) {
                    pendingWrite = null
                    applicationWrite.finish()
                }
            } else if (localFrameCount == 0) {
                aggregate.trySuccess()
            } else {
                localCombiner!!.finish(aggregate)
            }
            return aggregate
        }

        fun sendData(child: MuxChannel<ByteBuf>, data: ByteBuf): ChannelFuture {
            resetCause?.let { cause ->
                ReferenceCountUtil.release(data)
                throw cause
            }
            if (closedForWriting) {
                ReferenceCountUtil.release(data)
                throw ClosedForWritingMuxerException(id)
            }
            acknowledgeInboundStreamIfNeeded()
            check(pendingWrite == null) { "A Yamux stream may have only one child write in flight: $id" }
            val dataSize = data.readableBytes()
            check(activeChildWrites.put(data, dataSize) == null) {
                "Yamux child write became active more than once: $id"
            }
            activeChildWriteBytes += dataSize
            val write = PendingWrite(getChannelHandlerContext(), child)
            pendingWrite = write
            try {
                fillBuffer(data)
                drainBufferAndMaybeClose()
                return write.promise
            } catch (cause: Throwable) {
                if (pendingWrite === write) {
                    pendingWrite = null
                    write.fail(cause)
                }
                throw cause
            }
        }

        fun onLocalOpen() {
            writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, YamuxFlag.SYN.asSet, 0))
        }

        fun onRemoteOpen() {
            // nothing
        }

        fun onLocalDisconnect() {
            closedForWriting = true
            drainBufferAndMaybeClose()
        }

        fun onLocalClose(
            cause: Throwable = ConnectionClosedException("Yamux stream was reset with buffered data: $id")
        ) {
            // close stream immediately so not transferring buffered data
            val terminalCause = resetCause ?: cause
            resetCause = terminalCause
            dispose(terminalCause)
            if (resetSent) return
            resetSent = true
            writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, YamuxFlag.RST.asSet, 0))
        }
    }

    private val idGenerator = YamuxStreamIdGenerator(connectionInitiator)

    private val streamHandlers: MutableMap<MuxId, YamuxStreamHandler> = ConcurrentHashMap()
    private var pendingChildWriteBytes = 0L
    private var activeChildWriteBytes = 0L
    private val activeChildWrites = IdentityHashMap<ByteBuf, Int>()
    private var firstUnwritableNanos: Long? = null
    private var terminalWriteFailure: YamuxOutboundBufferExceededException? = null

    /**
     * Would contain GoAway error code when received, or would be completed with [ConnectionClosedException]
     * when the connection closed without GoAway message
     */
    val goAwayPromise = CompletableFuture<Long>()

    private fun getStreamHandlerOrThrow(id: MuxId): YamuxStreamHandler = getStreamHandlerOrReleaseAndThrow(id, null)

    private fun getStreamHandlerOrReleaseAndThrow(id: MuxId, msgToRelease: ByteBuf?): YamuxStreamHandler =
        streamHandlers[id] ?: run {
            if (msgToRelease != null) {
                releaseMessage(msgToRelease)
            }
            throw UnknownStreamIdMuxerException(id)
        }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        streamHandlers.values.forEach { it.dispose() }

        if (!goAwayPromise.isDone) {
            goAwayPromise.completeExceptionally(ConnectionClosedException("Connection was closed without Go Away message"))
        }
        super.channelUnregistered(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as YamuxFrame

        when (msg.type) {
            YamuxType.PING -> handlePing(msg)
            YamuxType.GO_AWAY -> handleGoAway(msg)
            else -> {
                if (YamuxFlag.SYN in msg.flags) {
                    // remote opens a new stream
                    validateSynRemoteMuxId(msg.id)
                    onRemoteYamuxOpen(msg.id)
                }

                getStreamHandlerOrReleaseAndThrow(msg.id, msg.data).handleFrameRead(msg)
            }
        }
    }

    private fun writeAndFlushFrame(yamuxFrame: YamuxFrame): ChannelFuture {
        val existingFailure = terminalWriteFailure
        if (existingFailure != null) {
            ReferenceCountUtil.release(yamuxFrame.data)
            throw existingFailure
        }

        val ctx = getChannelHandlerContext()
        val channel = ctx.channel()
        val outboundBuffer = channel.unsafe().outboundBuffer()
        val pendingBytes = outboundBuffer?.totalPendingWriteBytes() ?: 0L
        val frameBytes = YAMUX_HEADER_BYTES + (yamuxFrame.data?.readableBytes()?.toLong() ?: 0L)
        val queuedChildBytes = pendingChildWriteBytes - activeChildWriteBytes
        check(queuedChildBytes >= 0) {
            "Yamux child-write accounting has $activeChildWriteBytes active bytes but only " +
                "$pendingChildWriteBytes pending bytes"
        }
        val projectedPendingBytes =
            pendingBytes + frameBytes + queuedChildBytes + calculateTotalBufferedWrites()
        updateUnwritableStart(pendingBytes)

        if (projectedPendingBytes > maxBufferedConnectionWrites) {
            val cause = YamuxOutboundBufferExceededException(
                "Yamux parent outbound buffer exceeded configured budget; " +
                    "peer=${describeRemotePeer(ctx)}, pendingBytes=$pendingBytes, " +
                    "attemptedFrameBytes=$frameBytes, projectedPendingBytes=$projectedPendingBytes, " +
                    "budgetBytes=$maxBufferedConnectionWrites, " +
                    "overBudgetDurationMillis=${currentUnwritableDurationMillis()}, " +
                    "channel=$channel. Closing stalled connection."
            )
            terminalWriteFailure = cause
            channel.attr(WRITE_FAILURE).set(cause)
            ReferenceCountUtil.release(yamuxFrame.data)
            ctx.fireExceptionCaught(cause)
            // Let the throwing write unwind first so its retained slices are released before
            // connection close fails the child promise and makes that failure visible upstream.
            ctx.executor().execute { ctx.close() }
            throw cause
        }

        return ctx.writeAndFlush(yamuxFrame)
    }

    private fun updateUnwritableStart(pendingBytes: Long) {
        val channel = getChannelHandlerContext().channel()
        if (!channel.isWritable || pendingBytes > maxBufferedConnectionWrites) {
            if (firstUnwritableNanos == null) {
                firstUnwritableNanos = System.nanoTime()
            }
        } else {
            firstUnwritableNanos = null
        }
    }

    private fun currentUnwritableDurationMillis(): Long {
        return firstUnwritableNanos?.let { (System.nanoTime() - it) / 1_000_000L } ?: 0L
    }

    private fun describeRemotePeer(ctx: ChannelHandlerContext): String {
        val connection = ctx.channel().attr(CONNECTION).get()
        return connection?.secureSession()?.remoteId?.toString()
            ?: ctx.channel().remoteAddress()?.toString()
            ?: "unknown"
    }

    private fun abruptlyCloseConnection() {
        getChannelHandlerContext().close()
    }

    private fun validateSynRemoteMuxId(id: MuxId) {
        val isRemoteConnectionInitiator = !connectionInitiator
        if (!YamuxStreamIdGenerator.isRemoteSynStreamIdValid(isRemoteConnectionInitiator, id.id)) {
            abruptlyCloseConnection()
            throw Libp2pException("Invalid remote SYN StreamID: $id, isRemoteInitiator: $isRemoteConnectionInitiator")
        }
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf): ChannelFuture {
        return getStreamHandlerOrReleaseAndThrow(child.id, data).sendData(child, data)
    }

    override fun pendingChildWriteSize(data: ByteBuf): Int = data.readableBytes()

    override fun onPendingChildWrite(child: MuxChannel<ByteBuf>, dataSize: Int): Throwable? {
        val projectedBytes = pendingChildWriteBytes + dataSize
        if (projectedBytes <= maxBufferedConnectionWrites) {
            pendingChildWriteBytes = projectedBytes
            return null
        }

        val cause = WriteBufferOverflowMuxerException(
            "Overflowed send buffer ($projectedBytes/$maxBufferedConnectionWrites). " +
                "Last stream attempting to write: ${child.id}"
        )
        try {
            streamHandlers[child.id]?.onLocalClose(cause)
        } catch (resetFailure: Throwable) {
            if (resetFailure !== cause) cause.addSuppressed(resetFailure)
        } finally {
            child.close()
        }
        return cause
    }

    override fun onPendingChildWriteComplete(child: MuxChannel<ByteBuf>, data: ByteBuf, dataSize: Int) {
        pendingChildWriteBytes -= dataSize
        check(pendingChildWriteBytes >= 0) {
            "Yamux child outbound accounting underflowed by ${-pendingChildWriteBytes} bytes after completing ${child.id}"
        }
        activeChildWrites.remove(data)?.let { activeSize ->
            activeChildWriteBytes -= activeSize
            check(activeChildWriteBytes >= 0) {
                "Yamux active child-write accounting underflowed by ${-activeChildWriteBytes} bytes after completing ${child.id}"
            }
        }
    }

    override fun onLocalOpen(child: MuxChannel<ByteBuf>) {
        verifyAckBacklogLimitNotReached(child.id, true)
        createYamuxStreamHandler(child.id, true).onLocalOpen()
    }

    private fun onRemoteYamuxOpen(id: MuxId) {
        verifyAckBacklogLimitNotReached(id, false)
        createYamuxStreamHandler(id, false).onRemoteOpen()
        onRemoteOpen(id)
    }

    private fun verifyAckBacklogLimitNotReached(id: MuxId, outbound: Boolean) {
        val totalUnacknowledgedStreams =
            streamHandlers.values.count { it.outbound == outbound && !it.acknowledged.get() }
        if (totalUnacknowledgedStreams >= ackBacklogLimit) {
            throw AckBacklogLimitExceededMuxerException("The ACK backlog limit of $ackBacklogLimit streams has been reached. Will not open new stream: $id")
        }
    }

    private fun createYamuxStreamHandler(id: MuxId, outbound: Boolean): YamuxStreamHandler {
        val streamHandler = YamuxStreamHandler(id, outbound)
        streamHandlers[id] = streamHandler
        return streamHandler
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        getStreamHandlerOrThrow(child.id).onLocalDisconnect()
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        streamHandlers.remove(child.id)?.onLocalClose()
    }

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {
        streamHandlers.remove(child.id)?.dispose()
    }

    private fun handlePing(msg: YamuxFrame) {
        if (msg.id.id != YamuxId.SESSION_STREAM_ID) {
            throw InvalidFrameMuxerException("Invalid StreamId for Ping frame type: ${msg.id}")
        }
        if (YamuxFlag.SYN in msg.flags) {
            writeAndFlushFrame(
                YamuxFrame(
                    YamuxId.sessionId(msg.id.parentId),
                    YamuxType.PING,
                    YamuxFlag.ACK.asSet,
                    msg.length
                )
            )
        }
    }

    private fun handleGoAway(msg: YamuxFrame) {
        if (msg.id.id != YamuxId.SESSION_STREAM_ID) {
            throw InvalidFrameMuxerException("Invalid StreamId for GoAway frame type: ${msg.id}")
        }
        goAwayPromise.complete(msg.length)
    }

    private fun calculateTotalBufferedWrites(): Int {
        return streamHandlers.values.sumOf { it.sendBuffer.readableBytes() }
    }

    override fun generateNextId() =
        YamuxId(getChannelHandlerContext().channel().id(), idGenerator.next())
}
