package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkSelectionListenerTest {
    @Test
    fun `stream free reuse does not accumulate close dependents on a live parent`() {
        val a = listenerHost()
        val b = listenerHost()
        val inbound = CompletableFuture<Connection>()
        val requests = AtomicInteger()
        b.addConnectionHandler(ConnectionHandler.create { inbound.complete(it) })
        a.addStreamVisitor(
            ChannelVisitor { stream ->
                stream.getProtocol().thenAccept { protocol ->
                    if (protocol == "/libp2p/connection-selection/1.0.0" && requests.incrementAndGet() == 1) stream.reset()
                }
            }
        )
        try {
            a.start().get(10, TimeUnit.SECONDS)
            b.start().get(10, TimeUnit.SECONDS)
            val parent = a.network.connect(b.peerId, b.listenAddresses().single()).get(10, TimeUnit.SECONDS)
            val remote = inbound.get(10, TimeUnit.SECONDS)
            val ping = a.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), parent)
                .controller.get(10, TimeUnit.SECONDS)
            ping.ping().get(10, TimeUnit.SECONDS)
            // Quiescent setup barrier: the connection handler's ownership listener is installed.
            assertThat(b.network.connect(a.peerId).get(10, TimeUnit.SECONDS)).isSameAs(remote)
            val before = remote.closeFuture().numberOfDependents
            // Intentionally sequential: measure listener retention after each completed reuse.
            repeat(32) {
                assertThat(b.network.connect(a.peerId).get(10, TimeUnit.SECONDS)).isSameAs(remote)
            }
            val after = remote.closeFuture().numberOfDependents
            println("close dependents before=$before after=$after; peer requests=${requests.get()}")
            ping.ping().get(10, TimeUnit.SECONDS)
            assertThat(remote.closeFuture().isDone).isFalse()
            assertThat(after)
                .describedAs("Repeated stream-free reuse must not add unbounded close continuations to the same live parent")
                .isLessThanOrEqualTo(before + 1)
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

private fun listenerHost() = host {
    network { listen("/ip4/127.0.0.1/tcp/0") }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    protocols { add(Ping()) }
}
