package io.libp2p.crypto

import org.bouncycastle.crypto.CryptoServicesRegistrar
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom

private object NonBlockingBouncyCastleEntropy {
    init {
        // Bouncy Castle performs randomized EC point normalization outside the key generator's
        // caller-supplied RNG. Keep that internal blinding operation off the default NativePRNG too.
        CryptoServicesRegistrar.setSecureRandomProvider { createNonBlockingSecureRandom() }
    }
}

/**
 * Prefers a cryptographically secure random source that does not block on `/dev/random`.
 *
 * On entropy-starved hosts, the first seed of the JVM's default `NativePRNG` can block for
 * 9–15 seconds while reading `/dev/random`, delaying libp2p host startup and key generation.
 * `NativePRNGNonBlocking` reads only `/dev/urandom`, which is cryptographically equivalent to
 * `/dev/random` on any booted modern system, so this does not reduce security. Platforms that do
 * not provide this Unix-specific algorithm fall back to the platform default, which is
 * non-blocking in practice on Windows and macOS but is not guaranteed to be so by the Java API.
 */
internal fun nonBlockingSecureRandom(): SecureRandom = createNonBlockingSecureRandom()

internal fun configureNonBlockingBouncyCastleEntropy() {
    NonBlockingBouncyCastleEntropy
}

private fun createNonBlockingSecureRandom(): SecureRandom =
    try {
        SecureRandom.getInstance("NativePRNGNonBlocking")
    } catch (_: NoSuchAlgorithmException) {
        SecureRandom()
    }
