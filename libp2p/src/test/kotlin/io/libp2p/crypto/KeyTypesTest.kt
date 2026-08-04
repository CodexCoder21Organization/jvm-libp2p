package io.libp2p.crypto

import com.google.protobuf.ByteString
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.crypto.keys.generateEcdsaKeyPair
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.crypto.keys.generateRsaKeyPair
import io.libp2p.crypto.keys.generateSecp256k1KeyPair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

private val deterministicSeed = "jvm-libp2p supplied RNG contract".toByteArray()

private fun deterministicSecureRandom(): SecureRandom =
    SecureRandom.getInstance("SHA1PRNG").apply { setSeed(deterministicSeed) }

class KeyTypesTest {

    @Test
    fun ed25519() {
        val pair = generateEd25519KeyPair()
        val toSign = "G'day!".toByteArray()
        val signed = pair.first.sign(toSign)
        assertTrue(pair.second.verify(toSign, signed))
    }

    @Test
    fun rsa() {
        val pair = generateRsaKeyPair(2048)
        val toSign = "G'day!".toByteArray()
        val signed = pair.first.sign(toSign)
        assertTrue(pair.second.verify(toSign, signed))
    }

    @Test
    fun secp256k1() {
        val pair = generateSecp256k1KeyPair()
        val toSign = "G'day!".toByteArray()
        val signed = pair.first.sign(toSign)
        assertTrue(pair.second.verify(toSign, signed))
    }

    @Test
    fun ecdsa() {
        val pair = generateEcdsaKeyPair() // p-256
        val toSign = "G'day!".toByteArray()
        val signed = pair.first.sign(toSign)
        assertTrue(pair.second.verify(toSign, signed))
    }

    @ParameterizedTest
    @EnumSource(KeyType::class)
    fun genericGeneratorUsesCallerSuppliedRandom(type: KeyType) {
        val first = generateKeyPair(type, random = deterministicSecureRandom())
        val second = generateKeyPair(type, random = deterministicSecureRandom())

        assertEquals(ByteString.copyFrom(first.first.bytes()), ByteString.copyFrom(second.first.bytes()))
        assertEquals(ByteString.copyFrom(first.second.bytes()), ByteString.copyFrom(second.second.bytes()))
    }

    @Test
    fun directGeneratorsUseCallerSuppliedRandom() {
        val generators = listOf<(SecureRandom) -> Pair<PrivKey, PubKey>>(
            { generateRsaKeyPair(2048, it) },
            ::generateEd25519KeyPair,
            ::generateSecp256k1KeyPair,
            ::generateEcdsaKeyPair
        )

        generators.forEach { generator ->
            val first = generator(deterministicSecureRandom())
            val second = generator(deterministicSecureRandom())

            assertEquals(ByteString.copyFrom(first.first.bytes()), ByteString.copyFrom(second.first.bytes()))
            assertEquals(ByteString.copyFrom(first.second.bytes()), ByteString.copyFrom(second.second.bytes()))
        }
    }

    @Test
    fun concurrentEd25519GenerationProducesDistinctKeys() {
        val threadCount = 8
        val keysPerThread = 25
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val futures = List(threadCount) {
                executor.submit<List<ByteString>> {
                    start.await()
                    List(keysPerThread) { ByteString.copyFrom(generateEd25519KeyPair().first.bytes()) }
                }
            }
            start.countDown()
            val keys = futures.flatMap { it.get() }

            assertEquals(threadCount * keysPerThread, keys.size)
            assertEquals(keys.size, keys.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }
}
