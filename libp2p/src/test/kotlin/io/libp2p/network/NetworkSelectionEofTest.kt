package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val RETENTION_PROTOCOL = "/libp2p/connection-selection/1.0.0"

class NetworkSelectionEofTest {
    @Test
    fun `stream free reuse completes with an EOF selection responder configured`() {
        // Reuse needs no selection request. Keep an EOF responder configured as a guard
        // against making successful reuse depend on a response payload again.
        val a = eofHost(true)
        val b = eofHost(false)
        val inbound = CompletableFuture<Connection>()
        val readClosedHandled = CompletableFuture<Unit>()
        b.addConnectionHandler(ConnectionHandler.create { inbound.complete(it) })
        b.addStreamVisitor(
            ChannelVisitor { stream ->
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        ctx.fireUserEventTriggered(evt)
                        if (evt == RemoteWriteClosed) {
                            ctx.executor().execute { readClosedHandled.complete(Unit) }
                        }
                    }
                })
            }
        )
        try {
            a.start().get(10, TimeUnit.SECONDS)
            b.start().get(10, TimeUnit.SECONDS)
            val parent = a.network.connect(b.peerId, b.listenAddresses().single()).get(10, TimeUnit.SECONDS)
            inbound.get(10, TimeUnit.SECONDS)
            val ping = a.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), parent)
                .controller.get(10, TimeUnit.SECONDS)
            ping.ping().get(10, TimeUnit.SECONDS)
            val reuse = b.network.connect(a.peerId)
            if (!reuse.isDone) readClosedHandled.get(10, TimeUnit.SECONDS)
            // If a regressed implementation opened a request, the barrier above processes its EOF.
            // Correct stream-free reuse completes successfully on the exact inbound parent.
            ping.ping().get(10, TimeUnit.SECONDS)
            assertThat(parent.closeFuture().isDone).isFalse()
            assertThat(reuse.isDone)
                .describedAs("Network.connect reuse must finish with the existing inbound parent")
                .isTrue()
            assertThat(reuse.get(10, TimeUnit.SECONDS)).isSameAs(inbound.get(10, TimeUnit.SECONDS))
        } finally {
            try {
                a.stop().get(10, TimeUnit.SECONDS)
            } finally {
                b.stop().get(10, TimeUnit.SECONDS)
            }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private fun eofHost(endReply: Boolean): Host = host {
    network { listen("/ip4/127.0.0.1/tcp/0") }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers {
        add(
            StreamMuxerProtocol { multistream, protocols ->
                val bindings = if (endReply) {
                    protocols.filterNot { it.protocolDescriptor.matchesAny(listOf(RETENTION_PROTOCOL)) } +
                        object : StrictProtocolBinding<Unit>(RETENTION_PROTOCOL, EndRetentionReply()) {}
                } else {
                    protocols
                }
                StreamMuxerProtocol.getYamux().createMuxer(multistream, bindings)
            }
        )
    }
    protocols { add(Ping()) }
}

private class EndRetentionReply : ProtocolHandler<Unit>(Long.MAX_VALUE, Long.MAX_VALUE) {
    override fun onStartResponder(stream: Stream): CompletableFuture<Unit> {
        stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                stream.closeWrite()
            }
        })
        return CompletableFuture.completedFuture(Unit)
    }
}
