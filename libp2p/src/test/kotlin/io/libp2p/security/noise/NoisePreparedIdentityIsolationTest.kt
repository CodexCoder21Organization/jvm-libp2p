package io.libp2p.security.noise

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.etc.CONNECTION
import io.libp2p.tools.TestChannel
import io.netty.buffer.ByteBuf
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
