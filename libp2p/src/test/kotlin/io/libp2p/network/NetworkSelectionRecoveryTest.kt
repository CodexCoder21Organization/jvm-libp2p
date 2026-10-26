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

class NetworkSelectionRecoveryTest {
    @Test
    fun `stream free reuse remains usable with a selection reset visitor configured`() {
        val a = recoveryHost()
        val b = recoveryHost()
        val inbound = CompletableFuture<Connection>()
        val requests = AtomicInteger()
        b.addConnectionHandler(ConnectionHandler.create { inbound.complete(it) })
        a.addStreamVisitor(
            ChannelVisitor { stream ->
                stream.getProtocol().thenAccept { protocol ->
                    if (protocol == "/libp2p/connection-selection/1.0.0" && requests.incrementAndGet() == 1) {
                        stream.reset()
                    }
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
            // The reset visitor is a trap for unexpected selection traffic; reuse needs no child.
            assertThat(b.network.connect(a.peerId).get(10, TimeUnit.SECONDS)).isSameAs(remote)
            ping.ping().get(10, TimeUnit.SECONDS)
            assertThat(remote.closeFuture().isDone).isFalse()
            // Repeating stream-free reuse returns the same healthy physical parent.
            val recovered = b.network.connect(a.peerId).get(10, TimeUnit.SECONDS)
            assertThat(recovered).isSameAs(remote)
            ping.ping().get(10, TimeUnit.SECONDS)
        } finally {
            println("selection requests reaching the peer: ${requests.get()}")
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

private fun recoveryHost() = host {
    network { listen("/ip4/127.0.0.1/tcp/0") }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    protocols { add(Ping()) }
}
