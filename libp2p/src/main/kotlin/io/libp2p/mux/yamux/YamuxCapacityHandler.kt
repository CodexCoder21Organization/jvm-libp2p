package io.libp2p.mux.yamux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import java.util.concurrent.CompletableFuture

internal class YamuxCapacityHandler(
    multistreamProtocol: MultistreamProtocol,
    maxFrameDataLength: Int,
    ready: CompletableFuture<StreamMuxer.Session>,
    inboundStreamHandler: StreamHandler<*>,
    connectionInitiator: Boolean,
    maxBufferedConnectionWrites: Int,
    ackBacklogLimit: Int,
    private val inboundLimit: Int
) : YamuxHandler(
    multistreamProtocol,
    maxFrameDataLength,
    ready,
    inboundStreamHandler,
    connectionInitiator,
    maxBufferedConnectionWrites,
    ackBacklogLimit
) {
    override val capacityResetSupported: Boolean = true

    override fun isInboundCapacityReached(): Boolean = openInboundStreamCount() >= inboundLimit
}
