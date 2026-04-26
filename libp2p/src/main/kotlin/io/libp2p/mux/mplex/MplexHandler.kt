package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.types.sliceMaxSize
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * Default cap on concurrently-open inbound mplex substreams per connection. Sized
 * generously above any realistic legitimate workload (libp2p applications typically
 * have <100 concurrent streams per peer). The point of this cap is purely defensive:
 * to bound the impact of a peer that opens streams faster than they get cleaned up.
 */
const val DEFAULT_MAX_OPEN_INBOUND_STREAMS = 1024

open class MplexHandler(
    override val multistreamProtocol: MultistreamProtocol,
    override val maxFrameDataLength: Int,
    ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>,
    maxOpenInboundStreams: Int = DEFAULT_MAX_OPEN_INBOUND_STREAMS
) : MuxHandler(ready, inboundStreamHandler, maxOpenInboundStreams) {

    private val idGenerator = AtomicLong(0xF)

    override fun generateNextId() =
        MplexId(getChannelHandlerContext().channel().id(), idGenerator.incrementAndGet(), true)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as MplexFrame
        when (msg.flag.type) {
            MplexFlag.Type.OPEN -> onRemoteOpen(msg.id)
            MplexFlag.Type.CLOSE -> onRemoteDisconnect(msg.id)
            MplexFlag.Type.RESET -> onRemoteClose(msg.id)
            MplexFlag.Type.DATA -> childRead(msg.id, msg.data)
        }
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf) {
        val ctx = getChannelHandlerContext()
        data.sliceMaxSize(maxFrameDataLength)
            .map { frameSliceBuf ->
                MplexFrame.createDataFrame(child.id, frameSliceBuf)
            }.forEach { muxFrame ->
                ctx.write(muxFrame)
            }
        ctx.flush()
    }

    override fun onLocalOpen(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MplexFrame.createOpenFrame(child.id))
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MplexFrame.createCloseFrame(child.id))
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MplexFrame.createResetFrame(child.id))
    }

    override fun resetRemoteStream(id: MuxId) {
        getChannelHandlerContext().writeAndFlush(MplexFrame.createResetFrame(id))
    }

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {}
}
