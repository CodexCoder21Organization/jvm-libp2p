package io.libp2p.network

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.mux.yamux.YamuxCapacityStreamMuxer
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NetworkReuseCapacityTest {
    @Test
    fun `reusing a working inbound parent does not require another inbound stream at its initiator`() {
        val server = capacityReuseHost(512)
        val client = capacityReuseHost(0)
        val accepted = CompletableFuture<Connection>()
        server.addConnectionHandler(ConnectionHandler.create { accepted.complete(it) })
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val outbound = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(10, TimeUnit.SECONDS)
            val inbound = accepted.get(10, TimeUnit.SECONDS)
            val ping = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), outbound)
                .controller.get(10, TimeUnit.SECONDS)
            ping.ping().get(10, TimeUnit.SECONDS)

            val reused = server.network.connect(client.peerId).get(10, TimeUnit.SECONDS)
            assertThat(reused).isSameAs(inbound)
            assertThat(reused.closeFuture().isDone).isFalse()
            ping.ping().get(10, TimeUnit.SECONDS)
            assertThat(client.network.connections).hasSize(1)
            assertThat(server.network.connections).hasSize(1)
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
            (client.network.transports + server.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private fun capacityReuseHost(maxInbound: Int): Host = host {
    network { listen("/ip4/127.0.0.1/tcp/0") }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers {
        add(StreamMuxerProtocol { multistream, protocols ->
            YamuxCapacityStreamMuxer(
                multistream.createMultistream(protocols).toStreamHandler(),
                multistream,
                maxInboundStreams = maxInbound
            )
        })
    }
    protocols { add(Ping()) }
}
