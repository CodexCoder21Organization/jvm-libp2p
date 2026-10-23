package io.libp2p.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom

class NonBlockingSecureRandomTest {

    @Test
    fun prefersNativePrngNonBlockingWhenAvailable() {
        val expectedAlgorithm = try {
            SecureRandom.getInstance("NativePRNGNonBlocking").algorithm
        } catch (_: NoSuchAlgorithmException) {
            SecureRandom().algorithm
        }

        assertEquals(expectedAlgorithm, nonBlockingSecureRandom().algorithm)
    }

    @Test
    fun alwaysReturnsUsableSecureRandom() {
        val first = ByteArray(32)
        val second = ByteArray(32)

        nonBlockingSecureRandom().nextBytes(first)
        nonBlockingSecureRandom().nextBytes(second)

        assertFalse(first.all { it == 0.toByte() })
        assertFalse(second.all { it == 0.toByte() })
        assertFalse(first.contentEquals(second))
    }
}
