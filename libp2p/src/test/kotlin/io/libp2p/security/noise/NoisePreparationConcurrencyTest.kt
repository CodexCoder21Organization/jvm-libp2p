package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.transport.tcp.TcpTransport
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
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.LockSupport

class NoisePreparationConcurrencyTest {
    @Test
    fun `two first builders await one serialized initialization`() {
        val output = Files.createTempFile("noise-concurrent-preparation-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m",
            "-Dio.netty.eventLoopThreads=1",
            "-cp",
            System.getProperty("java.class.path"),
            "io.libp2p.security.noise.NoisePreparationConcurrencyProbe"
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(awaitNoiseProbe(process, output), "Concurrent cold builders did not finish")
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
        val diagnosticStart = System.nanoTime()
        fun observe(stage: String) = println("CONCURRENCY_STAGE=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - diagnosticStart)}ms $stage")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val factories = CountDownLatch(2)
        val transportEntries = CountDownLatch(2)
        val allowSecondInitialization = CountDownLatch(1)
        val transportNumber = AtomicInteger()
        val initializationCallers = AtomicReferenceArray<Thread>(2)
        val initializationExits = AtomicInteger()
        val callers = CountDownLatch(2)
        val published = AtomicInteger()
        val provider = NoisePreparationProvider {
            observe("provider entered")
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Concurrent preparation was not released" }
        }
        val executor = Executors.newFixedThreadPool(2)
        val results = mutableListOf<CompletableFuture<Host>>()
        Security.insertProviderAt(provider, 1)
        try {
            // Identity signing/linkage is fixture startup. Keep the role factories cold:
            // the real provider must still have seen no handshake digest allocation.
            val identityFixture = NoiseXXSecureChannel(generateKeyPair(KeyType.ED25519).first)
            check(identityFixture.protocolDescriptor.announceProtocols == listOf("/noise"))
            check(provider.allocations.get() == 0 && provider.initializations.get() == 0) {
                "Identity fixture initialized the handshake role factories"
            }
            observe("identity fixture prepared without role allocation")
            noiseProbePhase("READY")
            repeat(2) {
                results += CompletableFuture.supplyAsync({
                    observe("builder $it caller entered")
                    callers.countDown()
                    check(callers.await(10, TimeUnit.SECONDS)) { "Both builder callers did not enter" }
                    host {
                        identity { factory = { generateKeyPair(KeyType.ED25519).first } }
                        transports {
                            add { upgrader ->
                                val number = transportNumber.incrementAndGet()
                                observe("transport $number constructing")
                                object : TcpTransport(upgrader) {
                                    override fun initialize() {
                                        observe("transport $number initializing")
                                        if (number == 2) {
                                            check(allowSecondInitialization.await(10, TimeUnit.SECONDS)) {
                                                "Second transport initialization was not released"
                                            }
                                        }
                                        observe("transport $number requesting readiness")
                                        val pending = ready()
                                        check(!pending.isDone) { "Transport preparation finished before both builders entered" }
                                        transportEntries.countDown()
                                        initializationCallers.set(number - 1, Thread.currentThread())
                                        try {
                                            super.initialize()
                                            check(release.count == 0L) {
                                                "Initializer $number returned while preparation was held"
                                            }
                                        } finally {
                                            initializationExits.incrementAndGet()
                                        }
                                    }
                                }
                            }
                        }
                        secureChannels {
                            add { key, _ ->
                                observe("binding constructing")
                                NoiseXXSecureChannel(key).also {
                                    observe("binding constructed")
                                    factories.countDown()
                                }
                            }
                        }
                    }.also { published.incrementAndGet() }
                }, executor).whenComplete { _, failure ->
                    if (failure != null) failure.printStackTrace(System.out)
                }
            }
            observe("controller awaiting provider")
            check(entered.await(10, TimeUnit.SECONDS)) { "Cold builders did not initialize the SHA-256 provider" }
            check(factories.await(10, TimeUnit.SECONDS)) { "Both builders did not construct their Noise bindings" }
            allowSecondInitialization.countDown()
            check(transportEntries.await(10, TimeUnit.SECONDS)) {
                "Both builders did not reach transport initialization before preparation release"
            }
            check(results.none { it.isDone } && published.get() == 0) { "A builder published a host before preparation finished" }
            // Observe the two fixture-owned callers, not libp2p internals. After publishing
            // their thread here each caller has no fixture wait before initialize returns.
            // An entry latch alone could let the second caller run only after release.
            val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                check(initializationExits.get() == 0) {
                    "An initializer exited before both callers awaited preparation"
                }
                val bothWaiting = (0 until 2).all {
                    initializationCallers.get(it)?.state in listOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
                }
                if (bothWaiting) {
                    check(initializationExits.get() == 0) {
                        "An initializer exited before both callers awaited preparation"
                    }
                    break
                }
                check(System.nanoTime() < waitDeadline) {
                    "Both initializer callers did not block on held preparation"
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
            }
            check(results.none { it.isDone } && published.get() == 0) {
                "A builder published before both callers awaited preparation"
            }
            release.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS).start().get(10, TimeUnit.SECONDS) }
            check(provider.initializations.get() == 1) { "Expected one provider initialization, got ${provider.initializations.get()}" }
            check(provider.allocations.get() == 2) { "Expected one pair of role allocations across both builders, got ${provider.allocations.get()}" }
            check(provider.destroyed.get() == 2 && provider.duplicateDestructions.get() == 0) {
                "Temporary role digests were not destroyed exactly once"
            }
            check(provider.maxActive.get() == 1 && provider.active.get() == 0) { "Noise preparation overlapped or remained active" }
            check(!provider.onNettyExecutor.get()) { "Cold concurrent preparation occupied a Netty executor" }
            check(initializationExits.get() == 2) { "Both initialization calls did not finish after release" }
            check(published.get() == 2) { "Both builders did not publish after preparation" }
            noiseProbePhase("BODY_COMPLETE")
            println("CONCURRENT_BUILDERS=2 INITIALIZATIONS=1 ROLE_ALLOCATIONS=2 MAX_ACTIVE=1")
        } finally {
            allowSecondInitialization.countDown()
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Builder callers did not terminate" }
            try {
                results.forEach { result ->
                    if (result.isDone && !result.isCompletedExceptionally) result.get().stop().get(10, TimeUnit.SECONDS)
                }
            } finally {
                Security.removeProvider(provider.name)
                noiseProbePhase("CLEANUP_COMPLETE")
            }
        }
    }
}
