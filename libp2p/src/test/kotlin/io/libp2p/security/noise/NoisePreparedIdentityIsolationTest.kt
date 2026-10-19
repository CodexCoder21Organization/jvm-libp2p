package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.etc.CONNECTION
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.tools.TestChannel
import io.netty.buffer.ByteBuf
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NoisePreparedIdentityIsolationTest {
    @Test
    fun `one prepared binding still creates a fresh ephemeral key for each channel`() {
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
        val binding = NoiseXXSecureChannel(counted)
        assertEquals(1, signatures.get(), "The binding must prepare its identity before initializing any channel")
        val remote = PeerId.fromPubKey(generateKeyPair(KeyType.ED25519).second)
        val frames = List(2) { ordinal ->
            val channel = TestChannel(id = "prepared-ephemeral-$ordinal", initiator = true, remotePeerId = remote)
            try {
                binding.initChannel(channel.attr(CONNECTION).get(), NoiseXXSecureChannel.announce)
                channel.pipeline().fireChannelActive()
                channel.runPendingTasks()
                val chunks = mutableListOf<ByteArray>()
                while (true) {
                    val chunk = channel.readOutbound<ByteBuf>() ?: break
                    try {
                        chunks += ByteArray(chunk.readableBytes()).also { chunk.getBytes(chunk.readerIndex(), it) }
                    } finally {
                        ReferenceCountUtil.release(chunk)
                    }
                }
                assertTrue(chunks.isNotEmpty(), "Each initiator must emit a real first Noise frame")
                chunks.fold(ByteArray(0), ByteArray::plus)
            } finally {
                channel.finishAndReleaseAll()
            }
        }
        assertFalse(frames[0].contentEquals(frames[1]), "Sharing a prepared identity must not reuse a channel's ephemeral key")
        assertEquals(1, signatures.get(), "Channel initialization must not repeat the prepared signature")
    }

    @Test
    fun `prepared identity owns copies of caller mutable bytes`() {
        val process = ProcessBuilder(
            java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), NoisePreparedIdentityMutationChild::class.java.name
        ).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val text = output.get(5, TimeUnit.SECONDS)
            assertTrue(finished, "Prepared identity child must finish; output=$text")
            assertEquals(0, process.exitValue(), "Prepared identity child failed; output=$text")
            assertTrue(text.contains("Prepared identity copies retained across connections"), text)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}

object NoisePreparedIdentityMutationChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val original = NoiseXXSecureChannel.localStaticPrivateKey25519
        val saved = original.copyOf()
        val nodes = mutableListOf<Host>()
        try {
            val real = generateKeyPair(KeyType.ED25519).first
            val signatures = AtomicInteger()
            val returned = mutableListOf<ByteArray>()
            val counted = object : PrivKey(real.keyType) {
                override fun raw() = real.raw()
                override fun publicKey() = real.publicKey()
                override fun sign(data: ByteArray): ByteArray {
                    signatures.incrementAndGet()
                    return real.sign(data).also { returned += it }
                }
            }
            val client = host {
                identity { factory = { counted } }
                protocols { +Ping() }
            }.also { nodes += it }
            val server = host {
                identity { random(KeyType.ED25519) }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                protocols { +Ping() }
            }.also { nodes += it }
            nodes.forEach { it.start().get(10, TimeUnit.SECONDS) }
            assertEquals(1, signatures.get(), "The live host must already own one prepared identity")
            original.fill(0)
            NoiseXXSecureChannel.localStaticPrivateKey25519 = ByteArray(32) { 7 }
            returned.forEach { it.fill(0) }
            repeat(2) {
                val opened = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                opened.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS)
                opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                client.network.connections.single().close().get(10, TimeUnit.SECONDS)
            }
            assertEquals(1, signatures.get(), "Source-array mutation and reconnect must not replace the host's prepared identity")
            println("Prepared identity copies retained across connections")
        } finally {
            nodes.asReversed().forEach { it.stop().get(10, TimeUnit.SECONDS) }
            saved.copyInto(original)
            NoiseXXSecureChannel.localStaticPrivateKey25519 = original
        }
    }
}
