package io.libp2p.protocol

import io.libp2p.core.Stream
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler

class ProtocolMessageHandlerAdapter<TMessage>(
    private val stream: Stream,
    private val pmh: ProtocolMessageHandler<TMessage>
) : SimpleChannelInboundHandler<Any>() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        pmh.onActivated(stream)
    }

    override fun channelRead0(ctx: ChannelHandlerContext?, msg: Any) {
        @Suppress("UNCHECKED_CAST")
        pmh.onMessage(stream, msg as TMessage)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        pmh.onClosed(stream)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        pmh.onException(cause)
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt == RemoteWriteClosed) {
            pmh.onReadClosed(stream)
        }
        super.userEventTriggered(ctx, evt)
    }
}
