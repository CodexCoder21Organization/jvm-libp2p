package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.Libp2pException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.Channel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93bQuicDialPublicationTest {
    @Test
    fun shutdownFromTheDialVisitorPreventsLateConnectionPublication() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val accepted = CompletableFuture<Connection>()
        val published = AtomicInteger()
        val closing = CompletableFuture<CompletableFuture<Unit>>()
        val parent = CompletableFuture<Channel>()
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { accepted.complete(it) }, null)
                .get(10, TimeUnit.SECONDS)
            val dial =
                client.dial(
                    server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey())),
                    { published.incrementAndGet() },
                    { connection ->
                        parent.complete((connection as ConnectionOverNetty).nettyChannel.parent())
                        closing.complete(client.close())
                    }
                )
            val error = assertThrows(ExecutionException::class.java) { dial.get(10, TimeUnit.SECONDS) }
            assertTrue(error.cause is Libp2pException, "A stopped transport must reject its pending dial")
            assertEquals("Transport is closed", error.cause!!.message)
            closing.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            val udp = parent.get(10, TimeUnit.SECONDS)
            assertEquals(0, published.get(), "A dial visitor that shuts down must not publish a connection afterwards")
            assertTrue(udp.closeFuture().isDone, "The in-flight dial's UDP parent must settle physically")
            assertFalse(udp.isOpen)
            assertFalse(udp.isRegistered)
            assertTrue(udp.eventLoop().parent().isTerminated)
            assertEquals(0, client.activeConnections)
        } finally {
            try {
                client.close().get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
