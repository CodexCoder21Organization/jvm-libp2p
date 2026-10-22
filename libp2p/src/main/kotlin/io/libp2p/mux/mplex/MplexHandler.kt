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
import io.netty.util.ReferenceCountUtil
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
