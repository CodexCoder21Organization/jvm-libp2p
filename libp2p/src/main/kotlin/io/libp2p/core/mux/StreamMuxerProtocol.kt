package io.libp2p.core.mux

import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.mux.mplex.DEFAULT_MAX_OPEN_INBOUND_STREAMS
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.mux.yamux.DEFAULT_ACK_BACKLOG_LIMIT
import io.libp2p.mux.yamux.DEFAULT_MAX_BUFFERED_CONNECTION_WRITES
import io.libp2p.mux.yamux.YamuxStreamMuxer

fun interface StreamMuxerProtocol {

    fun createMuxer(multistreamProtocol: MultistreamProtocol, protocols: List<ProtocolBinding<*>>): StreamMuxer

    companion object {
        /** Mplex muxer with the default per-connection inbound stream cap. */
        @JvmStatic
        val Mplex = mplexWithMaxOpenInboundStreams(DEFAULT_MAX_OPEN_INBOUND_STREAMS)

        /**
         * Mplex muxer with an explicit per-connection inbound stream cap. Beyond
         * the cap, further OPEN frames are RESET back at the peer instead of
         * allocating a new Netty pipeline (which would otherwise pin native
         * direct buffer memory and risk OOM under load).
         */
        @JvmStatic
        fun mplexWithMaxOpenInboundStreams(maxOpenInboundStreams: Int): StreamMuxerProtocol {
            return StreamMuxerProtocol { multistreamProtocol, protocols ->
                MplexStreamMuxer(
                    multistreamProtocol.createMultistream(
                        protocols
                    ).toStreamHandler(),
                    multistreamProtocol,
                    maxOpenInboundStreams
                )
            }
        }

        /**
         * @param maxBufferedConnectionWrites the maximum amount of bytes in the write buffer per connection
         * @param ackBacklogLimit the maximum amount of opened streams per connection which have not been acknowledged
         */
        @JvmStatic
        @JvmOverloads
        fun getYamux(
            maxBufferedConnectionWrites: Int = DEFAULT_MAX_BUFFERED_CONNECTION_WRITES,
            ackBacklogLimit: Int = DEFAULT_ACK_BACKLOG_LIMIT
        ): StreamMuxerProtocol {
            return StreamMuxerProtocol { multistreamProtocol, protocols ->
                YamuxStreamMuxer(
                    multistreamProtocol.createMultistream(
                        protocols
                    ).toStreamHandler(),
                    multistreamProtocol,
                    maxBufferedConnectionWrites,
                    ackBacklogLimit
                )
            }
        }
    }
}
