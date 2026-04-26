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

const val UNBOUNDED_INBOUND_STREAMS = Int.MAX_VALUE

abstract class AbstractMuxHandler<TData>(
    /**
     * Maximum number of concurrently-open *inbound* (remote-initiated) substreams the
     * muxer will keep registered. Once reached, further OPEN frames are RESET back at
     * the peer instead of allocating a new child pipeline.
     *
     * Default is unbounded for backward compatibility; concrete muxers (mplex, yamux)
     * pass a sane production default.
     *
     * Why this exists: each accepted substream allocates a Netty `DefaultChannelPipeline`
     * with multistream-select handlers and pins a slice of a Netty `PoolChunk` (16 MB
     * native direct memory each). A misbehaving peer that opens streams faster than it
     * closes them — or that opens streams and never finishes multistream-select — will
     * exhaust the JVM's default direct buffer pool (which equals `-Xmx` when not set
     * explicitly) and cause `OutOfMemoryError: Cannot reserve N bytes of direct buffer
     * memory`. Bounding the per-connection inbound substream count caps the bleed.
     */
    private val maxOpenInboundStreams: Int = UNBOUNDED_INBOUND_STREAMS
) : ChannelInboundHandlerAdapter() {

    private val streamMap: MutableMap<MuxId, MuxChannel<TData>> = mutableMapOf()
    var ctx: ChannelHandlerContext? = null
    private val activeFuture = CompletableFuture<Void>()
    private var closed = false
    protected abstract val inboundInitializer: MuxChannelInitializer<TData>
    private val pendingReadComplete = mutableSetOf<MuxId>()

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
            // JVM `Error`s (OutOfMemoryError, StackOverflowError, etc.) leave the muxer
            // pipeline in an unrecoverable state — buffer pools may be exhausted, half
            // of the open child streams may be malformed. Swallowing them lets the
            // parent connection linger and accumulate more dead substreams (this is
            // exactly the OOM-leak loop diagnosed in the heap dump). Propagate so the
            // pipeline closes and `parentCloseFuture` cascade-closes children.
            cause is Error -> {
                log.warn("Fatal error in muxer pipeline; propagating to close parent connection", cause)
                ctx.fireExceptionCaught(cause)
            }
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
        val openInboundStreams = streamMap.values.count { !it.initiator }
        if (openInboundStreams >= maxOpenInboundStreams) {
            // Cap reached. RESET the new stream back at the peer and drop it on the
            // floor: do NOT allocate a child pipeline. Keeping the parent connection
            // open is intentional — bursts of stream-open are normal during peer
            // (re)connection and shouldn't tear down well-behaved peers.
            log.debug(
                "Refusing inbound stream id={}: per-connection cap of {} reached",
                id,
                maxOpenInboundStreams
            )
            resetRemoteStream(id)
            return
        }
        val child = createChild(
            id,
            initializer,
            false
        )
        onRemoteCreated(child)
    }

    /**
     * Send a RESET (mplex `RESET` frame / yamux `RST` flag) for a remote-initiated
     * stream that we are refusing to accept (e.g. because [maxOpenInboundStreams] has
     * been reached). The default is a no-op for backward compatibility; concrete
     * muxers should override.
     */
    protected open fun resetRemoteStream(id: MuxId) {}

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
        streamMap.remove(child.id)
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
