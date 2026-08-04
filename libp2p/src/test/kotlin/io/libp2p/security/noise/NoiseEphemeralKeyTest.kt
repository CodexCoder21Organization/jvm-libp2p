package io.libp2p.security.noise

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.etc.CONNECTION
import io.libp2p.tools.TestChannel
import io.netty.buffer.ByteBuf
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoiseEphemeralKeyTest {
    @Test
    fun usesFreshEphemeralKeyForEveryHandshake() {
        val (localPrivateKey, _) = generateKeyPair(KeyType.ED25519)
        val (_, remotePublicKey) = generateKeyPair(KeyType.ED25519)
        val remotePeerId = PeerId.fromPubKey(remotePublicKey)

        fun firstInitiatorFrame(id: String): ByteArray {
            val channel = TestChannel(id = id, initiator = true, remotePeerId = remotePeerId)
            try {
                NoiseXXSecureChannel(localPrivateKey)
                    .initChannel(channel.attr(CONNECTION).get(), NoiseXXSecureChannel.announce)
                channel.pipeline().fireChannelActive()
                channel.runPendingTasks()

                val chunks = buildList {
                    while (true) {
                        val chunk = channel.readOutbound<ByteBuf>() ?: break
                        add(
                            ByteArray(chunk.readableBytes()).also {
                                chunk.getBytes(chunk.readerIndex(), it)
                                ReferenceCountUtil.release(chunk)
                            }
                        )
                    }
                }
                assertTrue(chunks.isNotEmpty(), "The Noise initiator must emit its first handshake frame when the channel becomes active")
                return chunks.fold(ByteArray(0), ByteArray::plus)
            } finally {
                channel.finishAndReleaseAll()
            }
        }

        val first = firstInitiatorFrame("first-ephemeral")
        val second = firstInitiatorFrame("second-ephemeral")

        assertFalse(
            first.contentEquals(second),
            "Two Noise handshakes with the same identity and process-static key must emit different first frames"
        )
    }
}
