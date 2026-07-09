package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.types.sliceMaxSize
import io.libp2p.etc.util.netty.mux.DEFAULT_MAX_INBOUND_STREAMS
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.util.concurrent.PromiseCombiner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

open class MplexHandler(
    override val multistreamProtocol: MultistreamProtocol,
    override val maxFrameDataLength: Int,
    ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>,
    maxInboundStreams: Int = DEFAULT_MAX_INBOUND_STREAMS
) : MuxHandler(ready, inboundStreamHandler, maxInboundStreams) {

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

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf): ChannelFuture {
        val ctx = getChannelHandlerContext()
        val aggregate = ctx.newPromise()
        val combiner = PromiseCombiner(ctx.executor())
        data.sliceMaxSize(maxFrameDataLength)
            .map { frameSliceBuf ->
                MplexFrame.createDataFrame(child.id, frameSliceBuf)
            }.forEach { muxFrame ->
                combiner.add(ctx.write(muxFrame))
            }
        combiner.finish(aggregate)
        ctx.flush()
        return aggregate
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

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {}

    override fun resetRemoteSubstream(id: MuxId) {
        // Tell the peer to abandon the inbound substream we refused (over the inbound cap) without
        // ever building its MuxChannel/Negotiator scaffolding. Mirrors onLocalClose's reset frame.
        getChannelHandlerContext().writeAndFlush(MplexFrame.createResetFrame(id))
    }
}
