package io.libp2p.etc.util.netty.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.InternalErrorException
import io.libp2p.core.Libp2pException
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.hasCauseOfType
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

            child.remoteDisconnected -> {
                releaseMessage(msg)
                throw ConnectionClosedException("Channel with id $id was closed for sending by remote")
            }

            else -> {
                pendingReadComplete += id
                child.pipeline().fireChannelRead(msg)
            }
        }
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

    abstract fun onChildWrite(child: MuxChannel<TData>, data: TData)

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
        streamMap[id]?.onRemoteDisconnected()
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
