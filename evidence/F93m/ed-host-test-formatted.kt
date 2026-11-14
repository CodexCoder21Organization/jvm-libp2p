package io.libp2p.transport

import io.libp2p.core.dsl.host
import io.libp2p.transport.quic.QuicTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class F93mEd25519HostSetupTest {
    @Test
    fun twoHostsConnectWithTheEd25519TransportFactory() {
        val server = host {
            secureTransports { add(QuicTransport::Ed25519) }
            network { listen("/ip4/127.0.0.1/udp/0/quic-v1") }
        }
        val client = host { secureTransports { add(QuicTransport::Ed25519) } }
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(10, TimeUnit.SECONDS)
            assertEquals(server.peerId, connection.secureSession().remoteId)
        } finally {
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }
}
