package io.libp2p.mux.yamux

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.P2PChannel
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerDebug
import java.util.concurrent.CompletableFuture

/** Yamux with a per-child capacity result, enabled only by its distinct negotiated protocol ID. */
class YamuxCapacityStreamMuxer(
    val inboundStreamHandler: StreamHandler<*>,
    private val multistreamProtocol: MultistreamProtocol,
    private val maxBufferedConnectionWrites: Int = DEFAULT_MAX_BUFFERED_CONNECTION_WRITES,
    private val ackBacklogLimit: Int = DEFAULT_ACK_BACKLOG_LIMIT,
    private val maxInboundStreams: Int = io.libp2p.etc.util.netty.mux.DEFAULT_MAX_INBOUND_STREAMS
) : StreamMuxer, StreamMuxerDebug {

    override val protocolDescriptor = ProtocolDescriptor("/urlprotocol/yamux-capacity/1.0.0")
    override var muxFramesDebugHandler: ChannelVisitor<Connection>? = null

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> {
        require(selectedProtocol == "/urlprotocol/yamux-capacity/1.0.0") {
            "Capacity Yamux requires selected protocol /urlprotocol/yamux-capacity/1.0.0, but received $selectedProtocol"
        }
        require(maxInboundStreams >= 0) {
            "Maximum inbound streams must be nonnegative, but received $maxInboundStreams"
        }
        val muxSessionReady = CompletableFuture<StreamMuxer.Session>()

        val yamuxFrameCodec = YamuxFrameCodec()
        ch.pushHandler(yamuxFrameCodec)
        muxFramesDebugHandler?.also { it.visit(ch as Connection) }
        ch.pushHandler(
            YamuxCapacityHandler(
                multistreamProtocol,
                yamuxFrameCodec.maxFrameDataLength,
                muxSessionReady,
                inboundStreamHandler,
                ch.isInitiator,
                maxBufferedConnectionWrites,
                ackBacklogLimit,
                maxInboundStreams
            )
        )

        return muxSessionReady
    }
}
