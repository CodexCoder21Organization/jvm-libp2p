package io.libp2p.transport

import io.libp2p.core.Libp2pException
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.quic.QuicTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class F93cQuicListenShutdownTest {
    @Test
    fun closeDuringListenerCertificatePreparationPreventsLateListenerPublication() {
        val key = generateEd25519KeyPair().first
        val entered = CompletableFuture<Unit>()
        val release = CountDownLatch(1)
        val heldKey =
            object : PrivKey(key.keyType) {
                override fun raw() = key.raw()

                override fun publicKey() = key.publicKey()

                override fun sign(data: ByteArray): ByteArray {
                    entered.complete(Unit)
                    assertTrue(release.await(10, TimeUnit.SECONDS), "The fixture must release certificate signing")
                    return key.sign(data)
                }
            }
        val transport = QuicTransport.ECDSA(heldKey, emptyList())
        val pending =
            CompletableFuture.supplyAsync {
                transport.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null)
            }.thenCompose { it }
        try {
            entered.get(10, TimeUnit.SECONDS)
            transport.close().get(10, TimeUnit.SECONDS)
            assertEquals(0, transport.activeListeners)
            release.countDown()
            val error = assertThrows(ExecutionException::class.java) { pending.get(10, TimeUnit.SECONDS) }
            assertEquals(0, transport.activeListeners, "A stopped transport must not retain a late failed listener")
            assertTrue(error.cause is Libp2pException)
            assertEquals("Transport is closed", error.cause!!.message)
            assertTrue(transport.listenAddresses().isEmpty())
        } finally {
            release.countDown()
            // The body observes the pending error; cleanup only joins its worker before returning.
            pending.handle { _, _ -> Unit }.get(10, TimeUnit.SECONDS)
            transport.close().get(10, TimeUnit.SECONDS)
        }
    }
}
