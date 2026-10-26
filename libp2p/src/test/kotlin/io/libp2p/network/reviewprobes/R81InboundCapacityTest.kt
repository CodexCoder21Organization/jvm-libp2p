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

class R81InboundCapacityTest {
    @Test
    fun `inbound reuse does not require a reverse stream slot`() {
        val a = capacityHost(0)
        val b = capacityHost(1)
        val inbound = CompletableFuture<Connection>()
        b.addConnectionHandler(ConnectionHandler.create { inbound.complete(it) })
        try {
            a.start().get(10, TimeUnit.SECONDS)
            b.start().get(10, TimeUnit.SECONDS)
            val parent = a.network.connect(b.peerId, b.listenAddresses().single()).get(10, TimeUnit.SECONDS)
            val peerParent = inbound.get(10, TimeUnit.SECONDS)
            val ping = a.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), parent)
                .controller.get(10, TimeUnit.SECONDS)
            ping.ping().get(10, TimeUnit.SECONDS)
            assertThat(b.network.connect(a.peerId).get(10, TimeUnit.SECONDS)).isSameAs(peerParent)
            ping.ping().get(10, TimeUnit.SECONDS)
            assertThat(parent.closeFuture().isDone).isFalse()
            assertThat(peerParent.closeFuture().isDone).isFalse()
        } finally {
            try { a.stop().get(10, TimeUnit.SECONDS) } finally { b.stop().get(10, TimeUnit.SECONDS) }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private fun capacityHost(limit: Int): Host = host {
    network { listen("/ip4/127.0.0.1/tcp/0") }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers {
        add(StreamMuxerProtocol { multistream, protocols ->
            YamuxCapacityStreamMuxer(multistream.createMultistream(protocols).toStreamHandler(), multistream,
                maxInboundStreams = limit)
        })
    }
    protocols { add(Ping()) }
}
