package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NoiseHostIdentityPreparationTest {
    @Test
    fun `host startup prepares the Noise identity before any connection`() {
        val real = generateKeyPair(KeyType.ED25519).first
        val signatures = AtomicInteger()
        val counted = object : PrivKey(real.keyType) {
            override fun raw() = real.raw()
            override fun publicKey() = real.publicKey()
            override fun sign(data: ByteArray): ByteArray {
                signatures.incrementAndGet()
                return real.sign(data)
            }
        }
        val node = host {
            identity { factory = { counted } }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { +Ping() }
        }
        try {
            node.start().get(10, TimeUnit.SECONDS)
            assertEquals(1, signatures.get(), "Host startup must finish its one Noise identity signature before any connection")
            assertEquals(0, node.network.connections.size, "Identity preparation must not open a connection")
        } finally {
            node.stop().get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `parallel connections reuse one prepared identity and retain independent handshake state`() {
        val real = generateKeyPair(KeyType.ED25519).first
        val signatures = AtomicInteger()
        val counted = object : PrivKey(real.keyType) {
            override fun raw() = real.raw()
            override fun publicKey() = real.publicKey()
            override fun sign(data: ByteArray): ByteArray {
                signatures.incrementAndGet()
                return real.sign(data)
            }
        }
        val nodes = mutableListOf<Host>()
        try {
            val client = host {
                identity { factory = { counted } }
                protocols { +Ping() }
            }.also { nodes += it }
            val servers = List(4) {
                host {
                    identity { random(KeyType.ED25519) }
                    network { listen("/ip4/127.0.0.1/tcp/0") }
                    protocols { +Ping() }
                }.also { nodes += it }
            }
            nodes.forEach { it.start().get(10, TimeUnit.SECONDS) }
            val streams = servers.map { server ->
                client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
            }
            streams.forEach { it.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS) }
            assertEquals(4, client.network.connections.size, "Each peer must own a separate completed connection")
            assertEquals(1, signatures.get(), "Four independent Noise connections must reuse the host's one identity signature")
            streams.forEach { it.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS) }
        } finally {
            nodes.asReversed().forEach { it.stop().get(10, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `identity preparation reports the original signing failure before publishing a binding`() {
        val real = generateKeyPair(KeyType.ED25519).first
        val failure = IllegalStateException("Noise identity preparation could not sign with the supplied identity")
        val failing = object : PrivKey(real.keyType) {
            override fun raw() = real.raw()
            override fun publicKey() = real.publicKey()
            override fun sign(data: ByteArray): ByteArray = throw failure
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { NoiseXXSecureChannel(failing) })
    }
}
