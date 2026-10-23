package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.Security
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NoisePreparationConcurrencyTest {
    @Test
    fun `two first builders await one serialized initialization`() {
        val output = Files.createTempFile("noise-concurrent-preparation-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m", "-Dio.netty.eventLoopThreads=1",
            "-cp", System.getProperty("java.class.path"),
            "io.libp2p.security.noise.NoisePreparationConcurrencyProbe"
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Concurrent cold builders did not finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}

object NoisePreparationConcurrencyProbe {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val factories = CountDownLatch(2)
        val callers = CountDownLatch(2)
        val published = AtomicInteger()
        val provider = NoisePreparationProvider {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Concurrent preparation was not released" }
        }
        val executor = Executors.newFixedThreadPool(2)
        val results = mutableListOf<CompletableFuture<Host>>()
        Security.insertProviderAt(provider, 1)
        try {
            repeat(2) {
                results += CompletableFuture.supplyAsync({
                    callers.countDown()
                    check(callers.await(10, TimeUnit.SECONDS)) { "Both builder callers did not enter" }
                    host {
                        identity { factory = { generateKeyPair(KeyType.ED25519).first } }
                        secureChannels {
                            add { key, _ ->
                                NoiseXXSecureChannel(key).also { factories.countDown() }
                            }
                        }
                    }.also { published.incrementAndGet() }
                }, executor)
            }
            check(entered.await(10, TimeUnit.SECONDS)) { "Cold builders did not initialize the SHA-256 provider" }
            check(factories.await(10, TimeUnit.SECONDS)) { "Both builders did not construct their Noise bindings" }
            check(results.none { it.isDone } && published.get() == 0) { "A builder published a host before preparation finished" }
            release.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS).start().get(10, TimeUnit.SECONDS) }
            check(provider.initializations.get() == 1) { "Expected one provider initialization, got ${provider.initializations.get()}" }
            check(provider.allocations.get() == 2) { "Expected one pair of role allocations across both builders, got ${provider.allocations.get()}" }
            check(provider.destroyed.get() == 2 && provider.duplicateDestructions.get() == 0) {
                "Temporary role digests were not destroyed exactly once"
            }
            check(provider.maxActive.get() == 1 && provider.active.get() == 0) { "Noise preparation overlapped or remained active" }
            check(!provider.onNettyExecutor.get()) { "Cold concurrent preparation occupied a Netty executor" }
            check(published.get() == 2) { "Both builders did not publish after preparation" }
            println("CONCURRENT_BUILDERS=2 INITIALIZATIONS=1 ROLE_ALLOCATIONS=2 MAX_ACTIVE=1")
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Builder callers did not terminate" }
            try {
                results.forEach { result ->
                    if (result.isDone && !result.isCompletedExceptionally) result.get().stop().get(10, TimeUnit.SECONDS)
                }
            } finally {
                Security.removeProvider(provider.name)
            }
        }
    }
}
