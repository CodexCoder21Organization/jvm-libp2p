package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.types.sliceMaxSize
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.mux.MuxHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

open class MplexHandler(
    override val multistreamProtocol: MultistreamProtocol,
    override val maxFrameDataLength: Int,
    ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>,
    private val maxInboundStreams: Int = DEFAULT_MAX_INBOUND_STREAMS
) : MuxHandler(ready, inboundStreamHandler) {

    companion object {
        /**
         * Default per-connection cap on the number of concurrently-open mux streams.
         *
         * Mplex (unlike Yamux) has no flow control over the *number* of streams, so a
         * remote peer — or a storm of abandoned dials — can open inbound streams faster
         * than they negotiate or close. Each open stream retains a [MuxChannel] plus its
         * full multistream-negotiation pipeline (~5 KB) and a pending negotiation-timeout
         * task, so an unbounded number of inbound streams exhausts the heap.
         *
         * Production outages (ContainerNursery's embedded url:// node, `-Xmx128m`):
         * heap dumps repeatedly showed tens of thousands of [MuxChannel]s — ~500-720
         * inbound streams *per connection* — almost all stuck in multistream negotiation,
         * filling the 128 MB heap and taking down every HTTPS service the host fronts
         * (kotlin.directory, buildtest, the nursery API). A healthy connection multiplexes
         * a handful of streams; hundreds is pathological. This matches the libp2p
         * resource-manager default of 256 inbound streams per peer.
         */
        const val DEFAULT_MAX_INBOUND_STREAMS = 256
    }

    private val idGenerator = AtomicLong(0xF)

    override fun generateNextId() =
        MplexId(getChannelHandlerContext().channel().id(), idGenerator.incrementAndGet(), true)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as MplexFrame
        when (msg.flag.type) {
            MplexFlag.Type.OPEN ->
                if (getStreamCount() >= maxInboundStreams) {
                    // Per-connection concurrent-stream cap exceeded. Reset the new stream
                    // instead of tracking it, so a peer (or a storm of abandoned dials)
                    // cannot accumulate MuxChannels + their negotiation pipelines without
                    // bound and OOM the node. The MuxChannel scaffolding is never built;
                    // the remote observes a RESET for this stream id. See
                    // [DEFAULT_MAX_INBOUND_STREAMS] for the production background.
                    getChannelHandlerContext().writeAndFlush(MplexFrame.createResetFrame(msg.id))
                } else {
                    onRemoteOpen(msg.id)
                }
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

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {}
}
