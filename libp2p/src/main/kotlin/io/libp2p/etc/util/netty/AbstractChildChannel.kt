package io.libp2p.etc.util.netty

import io.netty.channel.AbstractChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelId
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelConfig
import io.netty.channel.EventLoop
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import java.net.SocketAddress

/**
 * Class representing 'virtual' channel which has a parent and
 * is closed automatically on parent close
 * Since this type of channels has no underlying transport connect() and bind() methods
 * are not supported
 */
abstract class AbstractChildChannel(parent: Channel, id: ChannelId?) : AbstractChannel(parent, id) {
    private enum class State {
        OPEN,
        ACTIVE,
        INACTIVE,
        CLOSED
    }

    private val parentCloseFuture = parent.closeFuture()
    private var state = State.OPEN
    private var closeImplicitly = false
    private val parentCloseListener = GenericFutureListener { _: Future<Void> -> closeImpl() }

    fun closeImpl() {
        closeImplicitly = true
        try {
            close()
        } finally {
            closeImplicitly = false
        }
    }

    override fun metadata(): ChannelMetadata = ChannelMetadata(false)
    override fun config(): ChannelConfig = DefaultChannelConfig(this)
    override fun isCompatible(loop: EventLoop?) = true

    override fun isOpen(): Boolean {
        return state != State.CLOSED
    }

    override fun isActive(): Boolean {
        return state == State.ACTIVE
    }

    override fun doRegister() {
        state = State.ACTIVE
        parentCloseFuture.addListener(parentCloseListener)
    }

    override fun doDeregister() {
        // NOOP
    }

    override fun doDisconnect() {
        if (!metadata().hasDisconnect()) {
            doClose()
        }
    }

    override fun doClose() {
        // The teardown below is in a `finally` because it must run even when the work above it fails.
        // `onClientClosed()` reaches the muxer — a yamux substream asks it to emit an RST frame, and that
        // write throws once the connection has latched a terminal write failure. Netty's
        // `AbstractUnsafe.doClose0` completes the channel's close future even when `doClose()` throws, and
        // latches `closeInitiated` so every later `close()` is a no-op. Without this `finally`, such a
        // channel reports itself closed forever while keeping its whole pipeline attached, and nothing
        // ever fires `channelUnregistered` or `handlerRemoved` on it again.
        //
        // Observed twice over (UrlResolver buildtest run 21bb281d): a substream opened at 21 ms, closed at
        // 28 ms, and the controller future its `Host.newStream` caller was blocked on was still not
        // completed at 5016 ms — `ProtocolSelect` fails that future only from `channelUnregistered`. The
        // same gap retains the closed channel's entire pipeline, which is the heap signature described
        // below; making the teardown synchronous was not enough on its own, it also has to be
        // unconditional.
        try {
            if (!closeImplicitly) onClientClosed()
            deactivate()
        } finally {
            completeTeardown()
        }
    }

    /**
     * Marks this channel closed and tears its pipeline down. Runs exactly once per close, whether or not
     * the rest of [doClose] succeeded.
     */
    private fun completeTeardown() {
        state = State.CLOSED
        parentCloseFuture.removeListener(parentCloseListener)

        // Synchronously fire channelUnregistered through the pipeline. Because
        // `state == CLOSED` now makes `isOpen()` false, the pipeline's
        // `fireChannelUnregistered` runs `destroy()` synchronously — removing every
        // handler and firing `handlerRemoved` on each — before close() returns.
        //
        // Why this is needed (ContainerNursery / kotlin.directory OOM, UrlProtocol #294):
        // `doDeregister()` is a no-op for child channels, so the standard close flow tears
        // the pipeline down only via the DEFERRED `fireChannelInactiveAndDeregister` that
        // `AbstractUnsafe.close()` posts with `invokeLater`. Under sustained inbound-stream
        // churn the event loop drains those deferred close tasks slower than new frames
        // arrive, so thousands of CLOSED MuxChannels keep their full multistream-negotiation
        // pipelines (handlers + decoders + direct buffers) queued in the loop's
        // MpscUnboundedArrayQueue until the heap is exhausted. The 2026-06-29 production
        // dump showed ~30K such closed channels retaining ~68 MB / 53% of a 128 MB heap.
        //
        // The standard deferred path still runs afterwards, but its second
        // `fireChannelUnregistered` is a no-op because the pipeline is already empty.
        pipeline().fireChannelUnregistered()
    }

    protected open fun onClientClosed() {}

    protected fun deactivate() {
        if (state == State.ACTIVE) {
            pipeline().fireChannelInactive()
            state = State.INACTIVE
        }
    }

    override fun doBeginRead() {
        // NOOP
    }

    override fun doBind(localAddress: SocketAddress?) {
        throw UnsupportedOperationException("ChildChannel doesn't support bind()")
    }

    override fun newUnsafe(): AbstractUnsafe = MUnsafe()

    private inner class MUnsafe : AbstractUnsafe() {
        override fun connect(remoteAddress: SocketAddress?, localAddress: SocketAddress?, promise: ChannelPromise?) {
            throw UnsupportedOperationException("ChildChannel doesn't support connect()")
        }
    }
}
