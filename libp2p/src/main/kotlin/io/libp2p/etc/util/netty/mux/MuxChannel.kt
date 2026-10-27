package io.libp2p.etc.util.netty.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.etc.util.netty.AbstractChildChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.channel.EventLoop
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.GlobalEventExecutor
import io.netty.util.concurrent.ScheduledFuture
import io.netty.util.concurrent.SingleThreadEventExecutor
import java.net.SocketAddress
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Alternative effort to start MultistreamChannel implementation from AbstractChannel
 */
class MuxChannel<TData>(
    private val parent: AbstractMuxHandler<TData>,
    val id: MuxId,
    private val initializer: MuxChannelInitializer<TData>,
    val initiator: Boolean
) : AbstractChildChannel(parent.ctx!!.channel(), id) {

    var remoteDisconnected = false
    var localDisconnected = false
    private var localDisconnectRequested = false
    private var waitingForParentWrite = false
    private var pendingParentWrite: ChannelFuture? = null
    private var parentWriteCompletion: ChildLoopCompletion? = null

    @Volatile
    private var acceptedWriteShutdown: ChildLoopCompletion? = null
    private val retainedWriteCount = AtomicInteger()

    internal fun resetForCapacity(): ChannelFuture = parent.resetForCapacity(this)

    override fun onAutoReadChanged() {
        parent.childAutoReadChanged(this)
    }

    override fun doBeginRead() {
        parent.childReadRequested(this)
    }

    override fun metadata(): ChannelMetadata = ChannelMetadata(true)
    override fun localAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().localAddress(), id)

    override fun remoteAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().remoteAddress(), id)

    override fun initChildPipeline() {
        pipeline().addFirst(PendingWriteAccountingHandler())
        initializer(this)
    }

    private inner class PendingWriteAccountingHandler : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (localDisconnectRequested || localDisconnected) {
                ReferenceCountUtil.release(msg)
                promise.tryFailure(ConnectionClosedException("The stream was closed for writing locally: $id"))
                return
            }
            @Suppress("UNCHECKED_CAST")
            val data = msg as TData
            val dataSize = parent.pendingChildWriteSize(data)
            if (dataSize == null) {
                writeAccepted(ctx, msg, promise)
                return
            }
            val writePromise = promise.unvoid()
            val failure = parent.onPendingChildWrite(this@MuxChannel, dataSize)
            if (failure != null) {
                ReferenceCountUtil.release(msg)
                writePromise.tryFailure(failure)
                return
            }

            writePromise.addListener {
                parent.onPendingChildWriteComplete(this@MuxChannel, data, dataSize)
            }
            writeAccepted(ctx, msg, writePromise)
        }

        override fun flush(ctx: ChannelHandlerContext) {
            ctx.flush()
            retireAcceptedWriteOwnerIfDrained()
        }
    }

    private fun writeAccepted(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        ensureAcceptedWriteTerminationOwner()
        retainedWriteCount.incrementAndGet()
        val bufferPromise = RetainedWritePromise(this, promise.unvoid()) {
            val remaining = retainedWriteCount.decrementAndGet()
            if (eventLoop().inEventLoop()) {
                retireAcceptedWriteOwnerIfDrained()
            } else if (remaining == 0) {
                acceptedWriteShutdown?.let(::dispatchLoopCompletion)
            }
        }
        ctx.write(msg, bufferPromise)
        retireAcceptedWriteOwnerIfDrained()
    }

    private fun ensureAcceptedWriteTerminationOwner() {
        // Accounting callbacks may close the child before its write reaches the buffer.
        if (!isOpen || unsafe().outboundBuffer() == null || acceptedWriteShutdown != null) return
        if (eventLoop() !is SingleThreadEventExecutor) return
        val shutdown = ChildLoopCompletion(
            eventLoop(),
            Runnable { retireAcceptedWriteOwnerIfDrained() },
            Runnable { settleWritesBeforeTermination() },
            // The separate parent completion owns preservation of its original result.
            Runnable { }
        )
        acceptedWriteShutdown = shutdown
        armLoopCompletion(shutdown)
    }

    private fun retireAcceptedWriteOwnerIfDrained() {
        // isEmpty counts flushed entries; private buffer promises also track unflushed entries.
        val buf = unsafe().outboundBuffer()
        if (buf == null || (retainedWriteCount.get() == 0 && buf.isEmpty)) {
            cancelAcceptedWriteTerminationOwner()
        }
    }

    private fun cancelAcceptedWriteTerminationOwner() {
        val shutdown = acceptedWriteShutdown ?: return
        acceptedWriteShutdown = null
        cancelLoopCompletion(shutdown)
    }

    private fun settleWritesBeforeTermination() {
        val buf = unsafe().outboundBuffer() ?: return
        cancelAcceptedWriteTerminationOwner()
        // A cancelled hook may already be in the executor's current shutdown batch.
        if (retainedWriteCount.get() == 0 && buf.isEmpty && pendingParentWrite == null) return
        localDisconnected = true
        parentWriteCompletion?.settleBeforeClose?.run()
        closeImpl()
        if (unsafe().outboundBuffer() === buf) {
            pendingParentWrite = null
            waitingForParentWrite = false
            parentWriteCompletion?.let(::cancelLoopCompletion)
            parentWriteCompletion = null
            val failure = RejectedExecutionException(
                "The child event loop terminated before pending writes could complete: $id"
            )
            // Include accepted writes which the caller has not flushed.
            buf.addFlush()
            while (buf.current() != null) buf.remove(failure)
        }
    }

    @Suppress("SwallowedException")
    override fun doWrite(buf: ChannelOutboundBuffer) {
        if (waitingForParentWrite) return

        while (true) {
            val msg = buf.current() ?: break
            if (localDisconnected) {
                // Must not throw from doWrite — exceptions escape uncaught to the Netty event loop.
                // Wrap buf.remove() defensively: in some Netty versions promise listeners triggered
                // by buf.remove() can propagate back through it.
                try {
                    buf.remove(ConnectionClosedException("The stream was closed for writing locally: $id"))
                } catch (e: Throwable) { }
                continue
            }
            try {
                // the msg is released by both onChildWrite and buf.remove() so we need to retain
                // however it is still to be confirmed that no buf leaks happen here TODO
                ReferenceCountUtil.retain(msg)
                @Suppress("UNCHECKED_CAST")
                val parentFuture = parent.onChildWrite(this, msg as TData)
                // The callback may synchronously close this child and release its buffer.
                if (!isOpen || unsafe().outboundBuffer() !== buf) return
                if (parentFuture.isDone) {
                    removeParentWrite(buf, parentFuture.causeOrNull())
                } else {
                    waitingForParentWrite = true
                    pendingParentWrite = parentFuture
                    val completeWrite = Runnable {
                        // Retained delivery and an accepted listener task can both reach this point.
                        // Close also clears ownership before disposing the outbound buffer.
                        if (pendingParentWrite === parentFuture && eventLoop().isShuttingDown) {
                            settleWritesBeforeTermination()
                        } else if (pendingParentWrite === parentFuture) {
                            pendingParentWrite = null
                            parentWriteCompletion?.let(::cancelLoopCompletion)
                            parentWriteCompletion = null
                            waitingForParentWrite = false
                            if (isOpen && unsafe().outboundBuffer() === buf) {
                                removeParentWrite(buf, parentFuture.causeOrNull())
                                if (isOpen) doWrite(buf)
                            }
                        }
                    }
                    val settleBeforeClose = Runnable {
                        if (pendingParentWrite === parentFuture && parentFuture.isDone) {
                            pendingParentWrite = null
                            parentWriteCompletion?.let(::cancelLoopCompletion)
                            parentWriteCompletion = null
                            waitingForParentWrite = false
                            removeParentWrite(buf, parentFuture.causeOrNull())
                        }
                    }
                    val completion = ChildLoopCompletion(
                        eventLoop(),
                        completeWrite,
                        Runnable { settleWritesBeforeTermination() },
                        settleBeforeClose
                    )
                    parentWriteCompletion = completion
                    armLoopCompletion(completion)
                    parentFuture.addListener { dispatchLoopCompletion(completion) }
                    break
                }
            } catch (cause: Throwable) {
                pendingParentWrite = null
                parentWriteCompletion?.let(::cancelLoopCompletion)
                parentWriteCompletion = null
                waitingForParentWrite = false
                buf.remove(cause)
            }
        }
        disconnectIfDrained()
        retireAcceptedWriteOwnerIfDrained()
    }

    private fun removeParentWrite(buf: ChannelOutboundBuffer, cause: Throwable?) {
        if (cause == null) {
            buf.remove()
        } else {
            buf.remove(cause)
        }
    }

    private fun io.netty.util.concurrent.Future<*>.causeOrNull(): Throwable? {
        return if (isSuccess) {
            null
        } else {
            cause() ?: ConnectionClosedException("Parent write failed without a cause: $id")
        }
    }

    override fun doDisconnect() {
        // Full close removes mux state; a repeated write close has no transition left to send.
        if (!isOpen || localDisconnectRequested || localDisconnected) return
        localDisconnectRequested = true
        // Include accepted, unflushed writes in the sequence before the muxer's FIN.
        unsafe().flush()
        disconnectIfDrained()
    }

    private fun disconnectIfDrained() {
        if (!isOpen || !localDisconnectRequested || localDisconnected || waitingForParentWrite) return
        if (unsafe().outboundBuffer()?.current() != null) return
        localDisconnected = true
        parent.localDisconnect(this)
        deactivate()
        closeIfBothDisconnected()
    }

    fun onRemoteDisconnected() {
        if (remoteDisconnected) return
        remoteDisconnected = true
        try {
            pipeline().fireUserEventTriggered(RemoteWriteClosed)
        } finally {
            closeIfBothDisconnected()
        }
    }

    override fun doClose() {
        // A completed parent outcome belongs to the accepted write even if delivery was rejected.
        // Set the write-side state before notifying callers, whose callbacks may reenter close.
        localDisconnected = true
        cancelAcceptedWriteTerminationOwner()
        parentWriteCompletion?.settleBeforeClose?.run()
        pendingParentWrite = null
        parentWriteCompletion?.let(::cancelLoopCompletion)
        parentWriteCompletion = null
        waitingForParentWrite = false
        // The muxer's own bookkeeping - removing this child from `streamMap` and releasing its slot in the
        // inbound-substream admission count - must happen even when the teardown above fails. Without the
        // `finally`, a close that throws leaks a `streamMap` entry and permanently consumes one of the 512
        // inbound slots, so "unconditional teardown" would hold for the pipeline but not for the muxer.
        try {
            super.doClose()
        } finally {
            parent.onClosed(this)
        }
    }

    override fun onClientClosed() {
        parent.localClose(this)
    }

    private fun closeIfBothDisconnected() {
        if (remoteDisconnected && localDisconnected) closeImpl()
    }
}

/** Netty settles this promise when it removes an entry, independently of caller completion. */
private class RetainedWritePromise(
    channel: Channel,
    private val caller: ChannelPromise,
    private val onSettled: () -> Unit
) : DefaultChannelPromise(channel) {
    override fun trySuccess(result: Void?): Boolean {
        if (!super.trySuccess(result)) return false
        onSettled()
        caller.trySuccess()
        return true
    }

    override fun setSuccess(result: Void?): ChannelPromise {
        super.setSuccess(result)
        onSettled()
        caller.trySuccess()
        return this
    }

    override fun tryFailure(cause: Throwable): Boolean {
        if (!super.tryFailure(cause)) return false
        onSettled()
        caller.tryFailure(cause)
        return true
    }

    override fun setFailure(cause: Throwable): ChannelPromise {
        super.setFailure(cause)
        onSettled()
        caller.tryFailure(cause)
        return this
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        if (!super.cancel(mayInterruptIfRunning)) return false
        onSettled()
        caller.cancel(mayInterruptIfRunning)
        return true
    }

    override fun setUncancellable(): Boolean {
        if (!caller.setUncancellable()) {
            // Netty will release this cancelled entry during addFlush().
            cancel(false)
            return false
        }
        return super.setUncancellable()
    }
}

private class ChildLoopCompletion(
    val loop: EventLoop,
    val operation: Runnable,
    val closeOnShutdown: Runnable,
    val settleBeforeClose: Runnable
) {
    @Volatile
    var active = true
    var retry: ScheduledFuture<*>? = null
}

private fun armLoopCompletion(completion: ChildLoopCompletion) {
    // These are registered from the owning loop, before a parent callback can arrive.
    // Caller-driven loops have no owned termination future or shutdown lifecycle.
    val executor = completion.loop as? SingleThreadEventExecutor ?: return
    executor.addShutdownHook(completion.closeOnShutdown)
}

private fun cancelLoopCompletion(completion: ChildLoopCompletion) {
    synchronized(completion) {
        completion.active = false
        completion.retry?.cancel(false)
        completion.retry = null
    }
    val executor = completion.loop as? SingleThreadEventExecutor ?: return
    if (!executor.isTerminated) {
        executor.removeShutdownHook(completion.closeOnShutdown)
    }
}

private fun dispatchLoopCompletion(completion: ChildLoopCompletion) {
    if (!completion.active) return
    try {
        completion.loop.execute(completion.operation)
    } catch (_: RejectedExecutionException) {
        // Queue rejection selects deferred delivery; callers still observe the parent
        // operation's retained result, so the scheduling signal is handled here.
        if (completion.loop.inEventLoop()) {
            completion.operation.run()
        } else if (!completion.loop.isShuttingDown) {
            // Only a rejected completion needs retained delivery. One scheduled attempt at a time
            // avoids consuming the child queue while its parent write is still pending.
            synchronized(completion) {
                if (completion.active && completion.retry == null) {
                    completion.retry = GlobalEventExecutor.INSTANCE.schedule(
                        {
                            synchronized(completion) { completion.retry = null }
                            dispatchLoopCompletion(completion)
                        },
                        10,
                        TimeUnit.MILLISECONDS
                    )
                }
            }
        }
        // Shutdown settles remaining writes on the owning loop before it stops.
    }
}

data class MultiplexSocketAddress(val parentAddress: SocketAddress, val streamId: MuxId) : SocketAddress() {
    override fun toString(): String {
        return "Mux[$parentAddress-$streamId]"
    }
}
