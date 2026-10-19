package io.libp2p.security.noise

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.etc.CONNECTION
import io.libp2p.tools.TestChannel
import io.netty.buffer.ByteBuf
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class NoiseEmbeddedComputationTest {
    @Test
    fun `embedded Noise computation stays synchronous and emits the first frame during activation`() {
        val (localPrivateKey, _) = generateKeyPair(KeyType.ED25519)
        val (_, remotePublicKey) = generateKeyPair(KeyType.ED25519)
        val remotePeerId = PeerId.fromPubKey(remotePublicKey)
        val callerThread = Thread.currentThread()
        val inInitialization = AtomicBoolean(false)
        val observations = ConcurrentLinkedQueue<EmbeddedNoiseComputationCallback>()
        val channel = TestChannel(
            id = "embedded-noise-computation",
            initiator = true,
            remotePeerId = remotePeerId
        )

        try {
            val binding = NoiseXXSecureChannel(localPrivateKey) { computation ->
                observations += EmbeddedNoiseComputationCallback(
                    thread = Thread.currentThread(),
                    inInitialization = inInitialization.get(),
                    computation = computation
                )
            }

            inInitialization.set(true)
            try {
                binding.initChannel(channel.attr(CONNECTION).get(), NoiseXXSecureChannel.announce)
                channel.pipeline().fireChannelActive()
            } finally {
                inInitialization.set(false)
            }

            // Inspect the synchronous contract before running pending embedded tasks or waiting.
            val initialObservations = observations.toList()
            assertEquals(
                1,
                initialObservations.size,
                "Exactly one Noise computation must be observed during embedded-channel initialization and activation"
            )
            val initialObservation = initialObservations.single()
            assertSame(
                callerThread,
                initialObservation.thread,
                "The initial embedded Noise computation must run on the caller thread"
            )
            assertTrue(
                initialObservation.inInitialization,
                "The initial computation callback must run while public channel initialization is on the stack"
            )
            assertTrue(
                initialObservation.computation.onEventLoop,
                "The synchronous embedded computation must report that it is on the channel event loop"
            )

            val firstFrame = readAllEmbeddedNoiseOutboundBytes(channel)
            assertEquals(
                34,
                firstFrame.size,
                "The first outbound Noise frame must contain a two-byte length prefix and a 32-byte ephemeral public key"
            )
            val declaredPayloadLength = ((firstFrame[0].toInt() and 0xFF) shl 8) or (firstFrame[1].toInt() and 0xFF)
            assertEquals(
                32,
                declaredPayloadLength,
                "The first outbound Noise frame must declare the 32-byte ephemeral public key payload"
            )
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}

private data class EmbeddedNoiseComputationCallback(
    val thread: Thread,
    val inInitialization: Boolean,
    val computation: NoiseHandshakeComputation
)

private fun readAllEmbeddedNoiseOutboundBytes(channel: TestChannel): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    while (true) {
        val chunk = channel.readOutbound<ByteBuf>() ?: break
        try {
            chunks += ByteArray(chunk.readableBytes()).also { bytes ->
                chunk.getBytes(chunk.readerIndex(), bytes)
            }
        } finally {
            ReferenceCountUtil.release(chunk)
        }
    }
    return chunks.fold(ByteArray(0), ByteArray::plus)
}
