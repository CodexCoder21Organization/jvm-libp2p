package io.libp2p.etc.util.netty.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.InternalErrorException
import io.libp2p.core.Libp2pException
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.hasCauseOfType
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

typealias MuxChannelInitializer<TData> = (MuxChannel<TData>) -> Unit

private val log = LoggerFactory.getLogger(AbstractMuxHandler::class.java)

/**
 * Default ceiling on the number of concurrently-open INBOUND (remote-initiated) substreams a
 * single connection may hold. See [AbstractMuxHandler.maxInboundStreams] for why this bound
 * exists; the value is a generous per-connection anti-monopoly limit (a healthy peer multiplexes
 * only a handful of substreams at once) chosen to keep the inbound-substream scaffolding heap
 * bounded to a few MB even on a small (e.g. 128 MB) consumer heap.
 */
const val DEFAULT_MAX_INBOUND_STREAMS: Int = 512

abstract class AbstractMuxHandler<TData>(
    /**
     * Maximum number of concurrently-open INBOUND (remote-initiated) substreams permitted on this
     * connection. When a remote peer opens a new inbound substream while this many are already open,
     * the new substream is refused (reset) by [onRemoteOpen] **before** the heavy
     * [MuxChannel] + multistream `Negotiator` + negotiation-timeout scaffolding is built, rather than
     * accepted and torn down afterwards.
     *
     * Why a hard bound here is necessary: jvm-libp2p builds that full per-substream scaffolding the
     * moment a NEW_STREAM frame is read, and the per-substream negotiation timeout that would
     * otherwise reclaim a never-completing inbound substream is a *scheduled task on this channel's
     * event loop*. Under a sustained inbound-substream flood (a reconnect / negotiation-abort herd,
     * or simply a peer opening substreams faster than they are handled) on a CPU-constrained host the
     * event loop spends its cycles creating new substreams and never drains those scheduled
     * reclamation tasks, so the scaffolding accumulates without bound until the heap is exhausted.
     * This was observed in production as tens of thousands of live MuxChannel /
     * Negotiator$ResponderHandler pipelines pinned by pending TotalTimeoutHandler tasks OOMing a
     * 128 MB ContainerNursery (UrlProtocol #294). Refusing excess inbound substreams at this layer
     * — synchronously, on the event loop, before any scaffolding exists — is the only thing that
     * bounds the heap regardless of how saturated the loop is.
     */
    private val maxInboundStreams: Int = DEFAULT_MAX_INBOUND_STREAMS
) : ChannelInboundHandlerAdapter() {

    private val streamMap: MutableMap<MuxId, MuxChannel<TData>> = mutableMapOf()
    var ctx: ChannelHandlerContext? = null
    private val activeFuture = CompletableFuture<Void>()
    private var closed = false
    protected abstract val inboundInitializer: MuxChannelInitializer<TData>
    private val pendingReadComplete = mutableSetOf<MuxId>()
    private class PendingInbound<TData> {
        val messages = java.util.ArrayDeque<TData>()
        var bytes = 0L
        var draining = false
        var remoteEndPending = false
    }
    private val pendingInbound = mutableMapOf<MuxId, PendingInbound<TData>>()
    private val pausedChildren = mutableSetOf<MuxId>()
    private var parentAutoReadBeforePause: Boolean? = null

    // A child config may be set by concurrent callers. Keep the saved parent setting and paused
    // child set as one state change even when an event loop reports several callers as in-loop.
    private val parentReadLock = Any()

    /** A finite limit for frames already decoded when parent reads are stopped. */
    protected open val maxPendingChildReadBytes: Long = 4L * 1024 * 1024

    /** Mplex bounds empty frames; windowed muxers may rely on their receive window instead. */
    protected open val maxPendingChildReadFrames: Int? = 64

    /** Size of one retained child payload. Production muxers override this for ByteBuf. */
    protected open fun pendingChildReadSize(data: TData): Int = 1

    /** Called only after the child pipeline has received a payload. */
    protected open fun onChildReadDelivered(id: MuxId, dataSize: Int) = Unit

    // Accessed only on this channel's single event-loop thread (same as streamMap), so plain vars
    // are sufficient — no synchronization needed.
    private var openInboundStreams: Int = 0
    private var rejectedInboundStreams: Long = 0

    /** Number of currently-open inbound (remote-initiated) substreams on this connection. */
    fun openInboundStreamCount(): Int = openInboundStreams

    /** Total inbound substreams refused for exceeding [maxInboundStreams] since this handler started. */
    fun rejectedInboundStreamCount(): Long = rejectedInboundStreams

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        super.handlerAdded(ctx)
        this.ctx = ctx
    }

    override fun channelActive(ctx: ChannelHandlerContext?) {
        activeFuture.complete(null)
        super.channelActive(ctx)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        activeFuture.completeExceptionally(ConnectionClosedException())
        closed = true
        val retained = pendingInbound.values.toList()
        pendingInbound.clear()
        retained.forEach { queued -> queued.messages.forEach(::releaseMessage) }
        synchronized(parentReadLock) {
            pausedChildren.clear()
            parentAutoReadBeforePause = null
        }
        super.channelUnregistered(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        when {
            cause.hasCauseOfType(InternalErrorException::class) -> log.warn("Muxer internal error", cause)
            cause.hasCauseOfType(Libp2pException::class) -> log.debug("Muxer exception", cause)
            else -> log.warn("Unexpected exception", cause)
        }
    }

    fun getChannelHandlerContext(): ChannelHandlerContext {
        return ctx
            ?: throw InternalErrorException("Internal error: handler context should be initialized at this stage")
    }

    protected fun childRead(id: MuxId, msg: TData) {
        val child = streamMap[id]
        when {
            child == null -> {
                releaseMessage(msg)
                throw ConnectionClosedException("Channel with id $id not opened")
            }

            child.remoteDisconnected || pendingInbound[id]?.remoteEndPending == true -> {
                releaseMessage(msg)
                throw ConnectionClosedException("Channel with id $id was closed for sending by remote")
            }

            else -> {
                if (!child.config().isAutoRead) pauseChild(child)
                val queued = pendingInbound[id]
                if (!child.config().isAutoRead || queued != null) {
                    val pending = queued ?: PendingInbound<TData>().also { pendingInbound[id] = it }
                    val size = pendingChildReadSize(msg)
                    val frameLimit = maxPendingChildReadFrames
                    if (pending.bytes + size > maxPendingChildReadBytes ||
                        (frameLimit != null && pending.messages.size >= frameLimit)
                    ) {
                        releaseMessage(msg)
                        log.warn(
                            "Paused child {} exceeded its inbound queue limit of {} bytes or {} frames; closing the child",
                            id,
                            maxPendingChildReadBytes,
                            frameLimit ?: "unlimited"
                        )
                        child.closeImpl()
                        return
                    }
                    pending.messages.add(msg)
                    pending.bytes += size
                    if (child.config().isAutoRead) drainPendingInbound(child)
                } else {
                    deliverToChild(child, msg, false)
                }
            }
        }
    }

    internal fun childAutoReadChanged(child: MuxChannel<TData>) {
        val parentContext = getChannelHandlerContext()
        val update = Runnable {
            if (child.isOpen) {
                if (child.config().isAutoRead) {
                    drainPendingInbound(child)
                    synchronized(parentReadLock) {
                        if (child.isOpen && child.config().isAutoRead) {
                            pausedChildren.remove(child.id)
                            restoreParentReadsIfPossible()
                        }
                    }
                } else {
                    pauseChild(child)
                }
            }
        }
        if (parentContext.executor().inEventLoop()) update.run() else parentContext.executor().execute(update)
    }

    private fun pauseChild(child: MuxChannel<TData>) {
        synchronized(parentReadLock) {
            if (!child.isOpen || child.config().isAutoRead || !pausedChildren.add(child.id)) return
            val parentChannel = getChannelHandlerContext().channel()
            if (parentAutoReadBeforePause == null) {
                parentAutoReadBeforePause = parentChannel.config().isAutoRead
            }
            parentChannel.config().isAutoRead = false
        }
    }

    /** Called while holding [parentReadLock]. */
    private fun restoreParentReadsIfPossible() {
        if (pausedChildren.isNotEmpty()) return
        val wasAutoRead = parentAutoReadBeforePause ?: return
        parentAutoReadBeforePause = null
        val parentChannel = getChannelHandlerContext().channel()
        if (wasAutoRead && parentChannel.isOpen) parentChannel.config().isAutoRead = true
    }

    private fun drainPendingInbound(child: MuxChannel<TData>) {
        val queued = pendingInbound[child.id] ?: return
        if (queued.draining) return
        var delivered = false
        queued.draining = true
        try {
            while (child.isOpen && child.config().isAutoRead && queued.messages.isNotEmpty()) {
                val msg = queued.messages.removeFirst()
                queued.bytes -= pendingChildReadSize(msg)
                delivered = true
                deliverToChild(child, msg, true)
            }
        } finally {
            queued.draining = false
            val remoteEndReady = queued.messages.isEmpty() && queued.remoteEndPending
            if (queued.messages.isEmpty() && !remoteEndReady && pendingInbound[child.id] === queued) {
                pendingInbound.remove(child.id)
            }
            try {
                if (delivered && child.isOpen) child.pipeline().fireChannelReadComplete()
            } finally {
                if (remoteEndReady) {
                    try {
                        if (child.isOpen) child.onRemoteDisconnected()
                    } finally {
                        if (pendingInbound[child.id] === queued) pendingInbound.remove(child.id)
                    }
                }
            }
        }
    }

    private fun deliverToChild(child: MuxChannel<TData>, msg: TData, fromQueue: Boolean) {
        val size = pendingChildReadSize(msg)
        if (!fromQueue) pendingReadComplete += child.id
        child.pipeline().fireChannelRead(msg)
        if (child.isOpen) onChildReadDelivered(child.id, size)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        pendingReadComplete.forEach { streamMap[it]?.pipeline()?.fireChannelReadComplete() }
        pendingReadComplete.clear()
    }

    /**
     * Needs to be called when message was not passed to the child channel pipeline due to any error.
     * (if a message was passed to the child channel it's the child channel's responsibility to release the message)
     */
    abstract fun releaseMessage(msg: TData)

    /** Returns retained payload bytes, or `null` when this muxer does not account child writes. */
    internal open fun pendingChildWriteSize(data: TData): Int? = null

    /**
     * Reserves connection-wide capacity before [data] enters the child channel's outbound buffer.
     * Returning a failure rejects that write without enqueueing it.
     */
    internal open fun onPendingChildWrite(
        child: MuxChannel<TData>,
        dataSize: Int
    ): Throwable? = null

    /** Releases a reservation made by [onPendingChildWrite] when the child promise completes. */
    internal open fun onPendingChildWriteComplete(
        child: MuxChannel<TData>,
        data: TData,
        dataSize: Int
    ) = Unit

    abstract fun onChildWrite(child: MuxChannel<TData>, data: TData): ChannelFuture

    protected fun onRemoteOpen(id: MuxId) {
        val initializer = inboundInitializer
        if (id in streamMap) {
            getChannelHandlerContext().close()
            throw Libp2pException("Remote party attempts to open a stream with existing id: $id")
        }
        if (openInboundStreams >= maxInboundStreams) {
            // Refuse the inbound substream BEFORE building the heavy MuxChannel + multistream
            // Negotiator + negotiation-timeout scaffolding (see [maxInboundStreams]). Resetting it
            // at the mux layer keeps the inbound-substream heap bounded even when the event loop is
            // saturated, which neither the per-substream negotiation timeout (a scheduled task that
            // starves under load) nor connection-level autoRead backpressure (which strands the
            // already-admitted, mid-negotiation substreams) can guarantee.
            rejectedInboundStreams++
            resetRemoteSubstream(id)
            return
        }
        // Reserve the slot before createChild: registration runs the inbound initializer
        // synchronously and could close the child immediately, firing onClosed (which decrements)
        // before we get here — incrementing first keeps the count symmetric in that race.
        openInboundStreams++
        val child = createChild(
            id,
            initializer,
            false
        )
        onRemoteCreated(child)
    }

    /**
     * Refuses an inbound substream that would exceed [maxInboundStreams], sending a mux-level reset
     * for [id] so the remote stops and the substream's scaffolding is never built on our side.
     * The default is a no-op (the heap is already protected by not creating the child); muxers that
     * can cheaply signal a reset for a bare id (e.g. mplex's RESET frame) override this.
     */
    protected open fun resetRemoteSubstream(id: MuxId) {}

    protected fun onRemoteDisconnect(id: MuxId) {
        // the channel could be RESET locally, so ignore remote CLOSE
        val child = streamMap[id] ?: return
        val queued = pendingInbound[id]
        if (child.remoteDisconnected || queued?.remoteEndPending == true) return
        if (queued != null) {
            queued.remoteEndPending = true
            if (child.config().isAutoRead) drainPendingInbound(child)
        } else {
            child.onRemoteDisconnected()
        }
    }

    protected fun onRemoteClose(id: MuxId) {
        // the channel could be RESET locally, so ignore remote RESET
        streamMap[id]?.closeImpl()
    }

    fun localDisconnect(child: MuxChannel<TData>) {
        onLocalDisconnect(child)
    }

    fun localClose(child: MuxChannel<TData>) {
        onLocalClose(child)
    }

    fun onClosed(child: MuxChannel<TData>) {
        pendingInbound.remove(child.id)?.messages?.forEach(::releaseMessage)
        synchronized(parentReadLock) {
            pausedChildren.remove(child.id)
            restoreParentReadsIfPossible()
        }
        if (streamMap.remove(child.id) != null && !child.initiator) {
            // An inbound (remote-initiated) substream closed (handled, reset, or negotiation
            // timed out): release its admission slot so a fresh inbound substream can take it.
            openInboundStreams--
        }
        onChildClosed(child)
    }

    abstract override fun channelRead(ctx: ChannelHandlerContext, msg: Any)
    protected open fun onRemoteCreated(child: MuxChannel<TData>) {}
    protected abstract fun onLocalOpen(child: MuxChannel<TData>)
    protected abstract fun onLocalClose(child: MuxChannel<TData>)
    protected abstract fun onLocalDisconnect(child: MuxChannel<TData>)
    protected abstract fun onChildClosed(child: MuxChannel<TData>)

    private fun createChild(
        id: MuxId,
        initializer: MuxChannelInitializer<TData>,
        initiator: Boolean
    ): MuxChannel<TData> {
        val child = MuxChannel(this, id, initializer, initiator)
        streamMap[id] = child
        ctx!!.channel().eventLoop().register(child).sync()
        return child
    }

    // protected open fun createChannel(id: MuxId, initializer: ChannelHandler) = MuxChannel(this, id, initializer)

    protected abstract fun generateNextId(): MuxId

    fun newStream(outboundInitializer: MuxChannelInitializer<TData>): CompletableFuture<MuxChannel<TData>> {
        try {
            checkClosed() // if already closed then event loop is already down and async task may never execute
            return activeFuture.thenApplyAsync(
                {
                    checkClosed() // close may happen after above check and before this point
                    val child = createChild(
                        generateNextId(),
                        {
                            onLocalOpen(it)
                            outboundInitializer(it)
                        },
                        true
                    )
                    child
                },
                getChannelHandlerContext().channel().eventLoop()
            )
        } catch (e: Exception) {
            return completedExceptionally(e)
        }
    }

    private fun checkClosed() =
        if (closed) throw ConnectionClosedException("Can't create a new stream: connection was closed: " + ctx!!.channel()) else Unit
}
