package io.libp2p.network

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS

class NetworkReservationClosingTest {
    @Test
    fun `reservation refuses an explicit close before physical close completes`() {
        val requested = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val a = host {
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Ping()) }
        }
        val b = host {
            network { listen("/ip4/127.0.0.1/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Ping()) }
        }
        val accepted = CompletableFuture<Connection>()
        b.addConnectionHandler(ConnectionHandler.create { accepted.complete(it) })
        try {
            a.start().get(10, SECONDS)
            b.start().get(10, SECONDS)
            val parent = a.network.connect(b.peerId, b.listenAddresses().single()).get(10, SECONDS)
            // The peer's publication is independent of local dial completion.
            val remote = accepted.get(10, SECONDS)
            assertThat(b.network.connections).contains(remote)
            remote.pushHandler(object : ChannelOutboundHandlerAdapter() {
                override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                    requested.complete(Unit)
                    release.thenRun { ctx.close(promise) }
                }
            })
            val closing = remote.close()
            requested.get(10, SECONDS)
            assertThat(remote.closeFuture().isDone).isFalse()
            assertThat(b.network.reserveConnection(remote).get(10, SECONDS)).isFalse()
            release.complete(Unit)
            closing.get(10, SECONDS)
            assertThat(b.network.reserveConnection(remote).get(10, SECONDS)).isFalse()
            assertThat(parent.closeFuture().get(10, SECONDS)).isEqualTo(Unit)
        } finally {
            release.complete(Unit)
            try {
                a.stop().get(10, SECONDS)
            } finally {
                b.stop().get(10, SECONDS)
            }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}
