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
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.PromiseCombiner
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

        fun dispose() {
            sendBuffer.dispose()
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
                onLocalClose()
                throw WriteBufferOverflowMuxerException(
                    "Overflowed send buffer ($totalBufferedWrites/$maxBufferedConnectionWrites). Last stream attempting to write: $id"
                )
            }
        }

        private fun drainBufferAndMaybeClose(): ChannelFuture {
            val ctx = getChannelHandlerContext()
            val aggregate = ctx.newPromise()
            val combiner = PromiseCombiner(ctx.executor())
            var futures = 0
            val maxSendLength = max(0, sendWindowSize.get())
            val data = sendBuffer.take(maxSendLength)
            sendWindowSize.addAndGet(-data.readableBytes())
            val slices = data.sliceMaxSize(maxFrameDataLength)
            var nextSliceIndex = 0
            try {
                slices.forEachIndexed { index, slicedData ->
                    nextSliceIndex = index + 1
                    val length = slicedData.readableBytes()
                    combiner.add(
                        writeAndFlushFrame(
                            YamuxFrame(id, YamuxType.DATA, YamuxFlag.NONE, length.toLong(), slicedData)
                        )
                    )
                    futures++
                }
            } catch (cause: Throwable) {
                for (index in nextSliceIndex until slices.size) {
                    ReferenceCountUtil.release(slices[index])
                }
                throw cause
            }

            if (closedForWriting && sendBuffer.readableBytes() == 0) {
                combiner.add(writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, YamuxFlag.FIN.asSet, 0)))
                futures++
            }
            if (futures == 0) {
                aggregate.setSuccess(null)
            } else {
                combiner.finish(aggregate)
            }
            return aggregate
        }

        fun sendData(data: ByteBuf): ChannelFuture {
            if (closedForWriting) {
                throw ClosedForWritingMuxerException(id)
            }
            acknowledgeInboundStreamIfNeeded()
            fillBuffer(data)
            return drainBufferAndMaybeClose()
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

        fun onLocalClose() {
            // close stream immediately so not transferring buffered data
            sendBuffer.dispose()
            writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, YamuxFlag.RST.asSet, 0))
        }
    }

    private val idGenerator = YamuxStreamIdGenerator(connectionInitiator)

    private val streamHandlers: MutableMap<MuxId, YamuxStreamHandler> = ConcurrentHashMap()
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
        val projectedPendingBytes = pendingBytes + frameBytes
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
            ctx.close()
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
        return getStreamHandlerOrReleaseAndThrow(child.id, data).sendData(data)
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
