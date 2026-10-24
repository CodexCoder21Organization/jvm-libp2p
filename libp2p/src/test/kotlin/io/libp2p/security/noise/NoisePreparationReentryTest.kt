package io.libp2p.security.noise

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.protocol.Ping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NoisePreparationReentryTest {
    @Test
    fun `connection callback can construct another Noise host on the same worker`() {
        val output = Files.createTempFile("noise-preparation-reentry-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m",
            "-Dio.netty.eventLoopThreads=1",
            "-cp",
            System.getProperty("java.class.path"),
            "io.libp2p.security.noise.NoisePreparationReentryProbe"
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(awaitNoiseProbe(process, output), "Host construction from its connection callback did not finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}

object NoisePreparationReentryProbe {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val nested = AtomicReference<Host>()
        val callback = CompletableFuture<Host>()
        val server = host {
            identity { random(KeyType.ED25519) }
            protocols { +Ping() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            connectionHandlers {
                +ConnectionHandler.create {
                    try {
                        val created = host {
                            identity { random(KeyType.ED25519) }
                            protocols { +Ping() }
                        }
                        nested.set(created)
                        created.start().get(10, TimeUnit.SECONDS)
                        callback.complete(created)
                    } catch (failure: Throwable) {
                        callback.completeExceptionally(failure)
                        throw failure
                    }
                }
            }
        }
        val client = host {
            identity { random(KeyType.ED25519) }
            protocols { +Ping() }
        }
        noiseProbePhase("READY")
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(10, TimeUnit.SECONDS)
            val created = callback.get(10, TimeUnit.SECONDS)
            check(created.network.connections.isEmpty()) { "Nested host construction must not open a preparation connection" }
            noiseProbePhase("BODY_COMPLETE")
            println("NOISE_PREPARATION_REENTRY_COMPLETE")
        } finally {
            try {
                nested.get()?.stop()?.get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    client.stop().get(10, TimeUnit.SECONDS)
                } finally {
                    server.stop().get(10, TimeUnit.SECONDS)
                    noiseProbePhase("CLEANUP_COMPLETE")
                }
            }
        }
    }
}
