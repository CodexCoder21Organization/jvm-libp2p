package io.libp2p.transport

import io.libp2p.core.Libp2pException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93cQuicBindShutdownTest {
    @Test
    fun shutdownWhileBindIsQueuedRejectsTheDialBeforeHandshakePublication() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val address = server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey()))
            val first = client.dial(address, { }, null).get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            val group = first.nettyChannel.parent().eventLoop().parent()
            first.close().get(10, TimeUnit.SECONDS)
            val heldLoops =
                group.map { loop ->
                    val entered = CompletableFuture<Unit>()
                    loop.execute {
                        entered.complete(Unit)
                        assertTrue(release.await(10, TimeUnit.SECONDS), "The fixture must release every held QUIC loop")
                    }
                    entered
                }
            heldLoops.forEach { it.get(10, TimeUnit.SECONDS) }
            val dial = client.dial(address, { published.incrementAndGet() }, null)
            assertFalse(dial.isDone, "Bind must still be queued when shutdown starts")
            val closing = client.close()
            release.countDown()
            val error = assertThrows(ExecutionException::class.java) { dial.get(10, TimeUnit.SECONDS) }
            assertTrue(error.cause is Libp2pException, "Transport shutdown must reject the pending dial with the transport-closed error")
            assertEquals("Transport is closed", error.cause!!.message)
            closing.get(10, TimeUnit.SECONDS)
            assertEquals(0, published.get(), "No queued dial may publish after shutdown")
            assertEquals(0, client.activeConnections)
            assertTrue(group.isTerminated)
            assertEquals(1, server.activeListeners, "The server listener must remain usable")
        } finally {
            release.countDown()
            try {
                client.close().get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
