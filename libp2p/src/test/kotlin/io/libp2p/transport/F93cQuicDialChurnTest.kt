package io.libp2p.transport

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class F93cQuicDialChurnTest {
    @Test
    fun closingChildrenReleasesTheirUdpParentsThroughoutLongLivedDialChurn() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null)
                .get(10, TimeUnit.SECONDS)
            val address = server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey()))
            // Successive batches deliberately reuse one live transport: history must not retain parents.
            repeat(8) {
                val connections = (0 until 16).map { client.dial(address, { }, null) }
                    .map { it.get(10, TimeUnit.SECONDS) as ConnectionOverNetty }
                val parents = connections.map { it.nettyChannel.parent() }
                val groups = parents.map { it.eventLoop().parent() }.distinct()
                assertEquals(1, groups.size)
                val group = groups.single()
                val threads = group.map { loop -> loop.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS) }
                connections.map { it.close() }.forEach { it.get(10, TimeUnit.SECONDS) }
                parents.forEach { parent ->
                    assertTrue(parent.closeFuture().await(10, TimeUnit.SECONDS), "Each completed dial must release its physical UDP parent while the transport remains usable")
                    assertTrue(parent.closeFuture().isSuccess)
                    parent.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
                    assertFalse(parent.isOpen)
                    assertFalse(parent.isRegistered)
                }
                assertEquals(0, client.activeConnections)
                assertFalse(group.isShuttingDown, "Churn must preserve the client group for the next dial")
                assertTrue(threads.all { it.isAlive }, "Churn must reuse the existing workers")
                assertEquals(1, server.activeListeners, "Churn must preserve the independent listener")
            }
        } finally {
            try {
                client.close().get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
