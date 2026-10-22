package io.libp2p.mux.yamux

import io.libp2p.core.P2PChannel
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerProtocol
import io.netty.buffer.ByteBuf
import java.util.concurrent.CompletableFuture

/**
 * Production Yamux with merging at the 64-payload bound turned off, so a paused stream at the bound
 * takes the same reset path Mplex takes. Used over real TCP to pin behaviour that merging would
 * otherwise mask, such as draining held payloads before admitting the next frame.
 */
internal fun mergeDeclinedYamux(): StreamMuxerProtocol = StreamMuxerProtocol { multistreamProtocol, protocols ->
    val inboundStreamHandler = multistreamProtocol.createMultistream(protocols).toStreamHandler()
    object : StreamMuxer {
        override val protocolDescriptor = ProtocolDescriptor("/yamux/1.0.0")

        override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> {
            val ready = CompletableFuture<StreamMuxer.Session>()
            val codec = YamuxFrameCodec()
            ch.pushHandler(codec)
            ch.pushHandler(
                object : YamuxHandler(
                    multistreamProtocol,
                    codec.maxFrameDataLength,
                    ready,
                    inboundStreamHandler,
                    ch.isInitiator,
                    DEFAULT_MAX_BUFFERED_CONNECTION_WRITES,
                    DEFAULT_ACK_BACKLOG_LIMIT
                ) {
                    override fun coalescePendingChildRead(tail: ByteBuf, data: ByteBuf): Boolean = false
                }
            )
            return ready
        }
    }
}
