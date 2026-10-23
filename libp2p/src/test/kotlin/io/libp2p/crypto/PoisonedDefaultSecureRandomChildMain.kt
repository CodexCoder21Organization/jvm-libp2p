package io.libp2p.crypto

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.crypto.keys.generateCurve25519KeyPair
import io.libp2p.crypto.keys.generateEcdsaKeyPair
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.crypto.keys.generateRsaKeyPair
import io.libp2p.crypto.keys.generateSecp256k1KeyPair
import io.libp2p.etc.CONNECTION
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.security.secio.SecIoNegotiator
import io.libp2p.tools.TestChannel
import java.security.Security

private fun completeNoiseHandshake() {
    val (initiatorPrivateKey, _) = generateKeyPair(KeyType.ED25519)
    val (responderPrivateKey, responderPublicKey) = generateKeyPair(KeyType.ED25519)
    val initiatorChannel = TestChannel(
        id = "poisoned-provider-initiator",
        initiator = true,
        remotePeerId = PeerId.fromPubKey(responderPublicKey)
    )
    val responderChannel = TestChannel(id = "poisoned-provider-responder", initiator = false)
    val initiator = NoiseXXSecureChannel(initiatorPrivateKey)
    val responder = NoiseXXSecureChannel(responderPrivateKey)
    val initiatorSession = initiator.initChannel(initiatorChannel.attr(CONNECTION).get(), NoiseXXSecureChannel.announce)
    val responderSession = responder.initChannel(responderChannel.attr(CONNECTION).get(), NoiseXXSecureChannel.announce)
    val connection = TestChannel.interConnect(initiatorChannel, responderChannel)

    try {
        initiatorChannel.pipeline().fireChannelActive()
        responderChannel.pipeline().fireChannelActive()
        initiatorSession.join()
        responderSession.join()
    } finally {
        connection.disconnect()
    }
}

object PoisonedDefaultSecureRandomChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Security.insertProviderAt(PoisonedDefaultSecureRandomProvider(), 1) == 1)

        generateCurve25519KeyPair()
        listOf(KeyType.RSA, KeyType.ED25519, KeyType.ECDSA, KeyType.SECP256K1).forEach(::generateKeyPair)
        generateRsaKeyPair(2048)
        generateEd25519KeyPair()
        generateEcdsaKeyPair()
        generateSecp256k1KeyPair()

        // This uses the same real in-process TestChannel wiring as NoiseSecureChannelTest, without
        // real sockets. A fresh JVM is essential because the Noise static key is process-static.
        completeNoiseHandshake()

        val (localPrivateKey, _) = generateKeyPair(KeyType.ED25519)
        val (_, remotePublicKey) = generateKeyPair(KeyType.ED25519)
        SecIoNegotiator({}, localPrivateKey, PeerId.fromPubKey(remotePublicKey))

        // TLS is intentionally excluded: JSSE may instantiate the default SecureRandom internally.
        // That JDK-owned path is outside jvm-libp2p's entropy-source call sites.
    }
}
