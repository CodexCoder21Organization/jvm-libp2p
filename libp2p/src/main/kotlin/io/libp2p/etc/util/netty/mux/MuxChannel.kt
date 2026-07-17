package io.libp2p.etc.util.netty.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.etc.util.netty.AbstractChildChannel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import java.net.SocketAddress

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
    private var waitingForParentWrite = false

    override fun metadata(): ChannelMetadata = ChannelMetadata(true)
    override fun localAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().localAddress(), id)

    override fun remoteAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().remoteAddress(), id)

    override fun doRegister() {
        super.doRegister()
        pipeline().addFirst(PendingWriteAccountingHandler())
        initializer(this)
    }

    private inner class PendingWriteAccountingHandler : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
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
                    parentFuture.addListener {
                        eventLoop().execute {
                            waitingForParentWrite = false
                            removeParentWrite(buf, parentFuture.causeOrNull())
                            doWrite(buf)
                        }
                    }
                    break
                }
            } catch (cause: Throwable) {
                buf.remove(cause)
            }
        }
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
        localDisconnected = true
        parent.localDisconnect(this)
        deactivate()
        closeIfBothDisconnected()
    }

    fun onRemoteDisconnected() {
        pipeline().fireUserEventTriggered(RemoteWriteClosed)
        remoteDisconnected = true
        closeIfBothDisconnected()
    }

    override fun doClose() {
        super.doClose()
        parent.onClosed(this)
    }

    override fun onClientClosed() {
        parent.localClose(this)
    }

    private fun closeIfBothDisconnected() {
        if (remoteDisconnected && localDisconnected) closeImpl()
    }
}

data class MultiplexSocketAddress(val parentAddress: SocketAddress, val streamId: MuxId) : SocketAddress() {
    override fun toString(): String {
        return "Mux[$parentAddress-$streamId]"
    }
}
