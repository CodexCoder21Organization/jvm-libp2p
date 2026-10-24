package io.libp2p.etc.util.netty.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.etc.util.netty.AbstractChildChannel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.EventLoop
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.GlobalEventExecutor
import io.netty.util.concurrent.ScheduledFuture
import io.netty.util.concurrent.SingleThreadEventExecutor
import java.net.SocketAddress
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

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
    private var parentWriteCompletion: ParentWriteCompletion? = null

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
                ctx.write(msg, promise)
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
            ctx.write(msg, writePromise)
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
                if (parentFuture.isDone) {
                    removeParentWrite(buf, parentFuture.causeOrNull())
                } else {
                    waitingForParentWrite = true
                    pendingParentWrite = parentFuture
                    val completeWrite = Runnable {
                        // Retained delivery and an accepted listener task can both reach this point.
                        // Close also clears ownership before disposing the outbound buffer.
                        if (pendingParentWrite === parentFuture) {
                            pendingParentWrite = null
                            parentWriteCompletion?.let(::cancelParentWriteCompletion)
                            parentWriteCompletion = null
                            waitingForParentWrite = false
                            if (isOpen && unsafe().outboundBuffer() === buf) {
                                removeParentWrite(buf, parentFuture.causeOrNull())
                                if (isOpen) doWrite(buf)
                            }
                        }
                    }
                    val completion = ParentWriteCompletion(
                        eventLoop(),
                        completeWrite,
                        Runnable { closeImpl() },
                        Runnable {
                            // Once the loop has terminated no owner operation can race disposal.
                            if (pendingParentWrite === parentFuture) {
                                pendingParentWrite = null
                                waitingForParentWrite = false
                                val failure = RejectedExecutionException(
                                    "The child event loop terminated before pending writes could complete: $id"
                                )
                                // Include accepted writes which the caller has not flushed.
                                buf.addFlush()
                                while (buf.current() != null) buf.remove(failure)
                                parentWriteCompletion = null
                            }
                        }
                    )
                    parentWriteCompletion = completion
                    armParentWriteCompletion(completion)
                    parentFuture.addListener { dispatchParentWriteCompletion(completion) }
                    break
                }
            } catch (cause: Throwable) {
                pendingParentWrite = null
                parentWriteCompletion?.let(::cancelParentWriteCompletion)
                parentWriteCompletion = null
                waitingForParentWrite = false
                buf.remove(cause)
            }
        }
        disconnectIfDrained()
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
        pendingParentWrite = null
        parentWriteCompletion?.let(::cancelParentWriteCompletion)
        parentWriteCompletion = null
        waitingForParentWrite = false
        // Close callbacks may call disconnect before the superclass finishes its teardown.
        localDisconnected = true
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

private class ParentWriteCompletion(
    val loop: EventLoop,
    val operation: Runnable,
    val closeOnShutdown: Runnable,
    val failAfterTermination: Runnable
) {
    @Volatile
    var active = true
    var retry: ScheduledFuture<*>? = null
    val terminationListener = GenericFutureListener<Future<Any?>> {
        if (active) {
            failAfterTermination.run()
            cancelParentWriteCompletion(this)
        }
    }
}

private fun armParentWriteCompletion(completion: ParentWriteCompletion) {
    // These are registered from the owning loop, before a parent callback can arrive.
    // Caller-driven loops have no owned termination future or shutdown lifecycle.
    val executor = completion.loop as? SingleThreadEventExecutor ?: return
    executor.addShutdownHook(completion.closeOnShutdown)
    executor.terminationFuture().addListener(completion.terminationListener)
}

private fun cancelParentWriteCompletion(completion: ParentWriteCompletion) {
    synchronized(completion) {
        completion.active = false
        completion.retry?.cancel(false)
        completion.retry = null
    }
    val executor = completion.loop as? SingleThreadEventExecutor ?: return
    if (!executor.isTerminated) {
        executor.removeShutdownHook(completion.closeOnShutdown)
    }
    executor.terminationFuture().removeListener(completion.terminationListener)
}

private fun dispatchParentWriteCompletion(completion: ParentWriteCompletion) {
    if (!completion.active) return
    try {
        completion.loop.execute(completion.operation)
    } catch (rejected: RejectedExecutionException) {
        if (completion.loop.inEventLoop()) {
            completion.operation.run()
        } else if (!completion.loop.isShuttingDown) {
            // Only a rejected completion needs retained delivery. One scheduled attempt at a time
            // avoids consuming the child queue while its parent write is still pending.
            synchronized(completion) {
                if (completion.active) {
                    completion.retry = GlobalEventExecutor.INSTANCE.schedule(
                        { dispatchParentWriteCompletion(completion) },
                        10,
                        TimeUnit.MILLISECONDS
                    )
                }
            }
        }
        // Shutdown closes on the owning loop; termination also disposes any remaining writes.
    }
}

data class MultiplexSocketAddress(val parentAddress: SocketAddress, val streamId: MuxId) : SocketAddress() {
    override fun toString(): String {
        return "Mux[$parentAddress-$streamId]"
    }
}
