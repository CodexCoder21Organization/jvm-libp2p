package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
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
import java.util.concurrent.TimeUnit

class R81EarlyCloseTest {
    @Test
    fun `a close requested before mux setup still excludes later reuse`() {
        val a = earlyCloseHost()
        val b = earlyCloseHost()
        val gate = EarlyPhysicalCloseGate()
        val firstSeen = CompletableFuture<Connection>()
        try {
            a.start().get(10, TimeUnit.SECONDS)
            b.start().get(10, TimeUnit.SECONDS)
            // A public pre-handler may cancel a candidate before its upgrade completes.
            val original = a.network.connect(b.peerId, ChannelVisitor { candidate ->
                candidate.pushHandler(gate)
                firstSeen.complete(candidate as Connection)
                candidate.close()
            }, b.listenAddresses().single())
            gate.requested.get(10, TimeUnit.SECONDS)
            original.get(10, TimeUnit.SECONDS)
            val old = firstSeen.get(10, TimeUnit.SECONDS)
            assertThat(old.closeFuture().isDone).isFalse()
            val selected = a.network.connect(b.peerId, b.listenAddresses().single()).get(10, TimeUnit.SECONDS)
            assertThat(selected)
                .describedAs("A new caller must not reuse a parent whose close was already requested before upgrade")
                .isNotSameAs(old)
            gate.release.complete(Unit)
            old.closeFuture().get(10, TimeUnit.SECONDS)
            assertThat(selected.closeFuture().isDone).isFalse()
        } finally {
            gate.release.complete(Unit)
            try { a.stop().get(10, TimeUnit.SECONDS) } finally { b.stop().get(10, TimeUnit.SECONDS) }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private fun earlyCloseHost() = host {
    network { listen("/ip4/127.0.0.1/tcp/0") }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    protocols { add(Ping()) }
}

private class EarlyPhysicalCloseGate : ChannelOutboundHandlerAdapter() {
    val requested = CompletableFuture<Unit>()
    val release = CompletableFuture<Unit>()
    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        requested.complete(Unit)
        release.thenRun { ctx.close(promise) }
    }
}
