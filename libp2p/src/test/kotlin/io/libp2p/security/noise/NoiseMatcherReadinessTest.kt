package io.libp2p.security.noise

import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.Security
import java.util.concurrent.TimeUnit

class NoiseMatcherReadinessTest {
    @Test
    fun `matcher only Noise receiver prepares before accepting its first real stream`() {
        val peer = host {
            identity { random(KeyType.ED25519) }
            protocols { +Ping() }
        }
        try {
            peer.start().get(10, TimeUnit.SECONDS)
            val output = Files.createTempFile("matcher-noise-", ".log")
            val process = matcherProcess().redirectErrorStream(true).redirectOutput(output.toFile()).start()
            val observation = java.util.concurrent.CompletableFuture.supplyAsync { awaitNoiseProbe(process, output) }
            var primaryFailure: Throwable? = null
            try {
                val endpointDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                var endpoint: String? = null
                while (endpoint == null) {
                    val alive = process.isAlive
                    val text = Files.readString(output)
                    endpoint = text.lineSequence().firstOrNull { it.startsWith("MATCHER_RECEIVER=") }
                        ?.removePrefix("MATCHER_RECEIVER=")
                    check(endpoint != null || alive) { "Matcher-only receiver exited before readiness:\n$text" }
                    check(endpoint != null || System.nanoTime() < endpointDeadline) {
                        "Matcher-only receiver did not publish its endpoint:\n$text"
                    }
                    if (endpoint == null) Thread.sleep(1)
                }
                val address = Multiaddr(requireNotNull(endpoint))
                val stream = peer.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), requireNotNull(address.getPeerId()), address)
                try {
                    check(stream.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS) >= 0)
                } finally {
                    stream.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                }
                process.outputStream.write(1)
                process.outputStream.flush()
                assertTrue(observation.get(30, TimeUnit.SECONDS), "Matcher-only readiness probe did not finish")
                val text = Files.readString(output)
                print(text)
                assertEquals(0, process.exitValue(), text)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                } finally {
                    try {
                        // Reap the observer before deleting the file it is reading.
                        observation.get(5, TimeUnit.SECONDS)
                    } catch (cleanupFailure: Throwable) {
                        val original = primaryFailure
                        if (original == null) throw cleanupFailure
                        if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
                    } finally {
                        Files.deleteIfExists(output)
                    }
                }
            }
        } finally {
            peer.stop().get(10, TimeUnit.SECONDS)
        }
    }
}

private fun matcherProcess(vararg args: String) = ProcessBuilder(
    listOf(
        File(System.getProperty("java.home"), "bin/java").absolutePath,
        "-Xmx128m",
        "-Dio.netty.eventLoopThreads=1",
        "-cp",
        System.getProperty("java.class.path"),
        "io.libp2p.security.noise.NoiseMatcherReadinessProbe"
    ) + args
)

object NoiseMatcherReadinessProbe {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val provider = NoisePreparationProvider()
        var server: io.libp2p.core.Host? = null
        check(Security.insertProviderAt(provider, 1) == 1)
        noiseProbePhase("READY")
        try {
            val receiver = host {
                identity { random(KeyType.ED25519) }
                protocols { +Ping() }
                secureChannels {
                    add { key, _ ->
                        val noise = NoiseXXSecureChannel(key)
                        object : SecureChannel by noise {
                            override val protocolDescriptor = ProtocolDescriptor(ProtocolMatcher.strict("/noise"))
                        }
                    }
                }
                network { listen("/ip4/127.0.0.1/tcp/0") }
            }
            server = receiver
            check(provider.allocations.get() == 2) {
                "Matcher-only Noise host returned before preparing both roles: allocations=${provider.allocations.get()}"
            }
            check(provider.initializations.get() == 1 && !provider.onNettyExecutor.get()) {
                "Matcher-only Noise preparation must finish outside a Netty executor before readiness"
            }
            check(provider.destroyed.get() == 2 && provider.duplicateDestructions.get() == 0)
            receiver.start().get(10, TimeUnit.SECONDS)
            check(receiver.network.connections.isEmpty())
            println("MATCHER_RECEIVER=${receiver.listenAddresses().single()}")
            System.out.flush()
            java.util.concurrent.CompletableFuture.supplyAsync { System.`in`.read() }.get(20, TimeUnit.SECONDS)
            check(provider.initializations.get() == 1) { "Incoming Noise stream initialized algorithms after readiness" }
            noiseProbePhase("BODY_COMPLETE")
            println("MATCHER_ONLY_PREPARED_BEFORE_READY=true")
        } finally {
            try {
                server?.stop()?.get(10, TimeUnit.SECONDS)
            } finally {
                Security.removeProvider(provider.name)
                noiseProbePhase("CLEANUP_COMPLETE")
            }
        }
    }
}
