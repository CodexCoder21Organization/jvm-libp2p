package io.libp2p.security.noise

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NoisePreparationCallbacksTest {
    @Test
    fun `preparing Noise factories does not invoke connection stream or computation callbacks`() {
        val output = Files.createTempFile("noise-preparation-callbacks-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m",
            "-cp",
            System.getProperty("java.class.path"),
            "io.libp2p.security.noise.NoisePreparationCallbacksProbe"
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Noise callback probe did not finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}

object NoisePreparationCallbacksProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val computations = AtomicInteger()
        val connections = CountDownLatch(2)
        val streams = CountDownLatch(2)
        val client = host {
            identity { random(KeyType.ED25519) }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) { computations.incrementAndGet() } } }
            muxers { +StreamMuxerProtocol.getYamux() }
            protocols { +Ping() }
            connectionHandlers { +ConnectionHandler { connections.countDown() } }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val server = host {
            identity { random(KeyType.ED25519) }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) { computations.incrementAndGet() } } }
            muxers { +StreamMuxerProtocol.getYamux() }
            protocols { +Ping() }
            connectionHandlers { +ConnectionHandler { connections.countDown() } }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        client.addStreamVisitor { streams.countDown() }
        server.addStreamVisitor { streams.countDown() }
        try {
            client.start().get(10, TimeUnit.SECONDS)
            server.start().get(10, TimeUnit.SECONDS)
            check(computations.get() == 0 && connections.count == 2L && streams.count == 2L) {
                "Noise preparation invoked a user callback: computations=${computations.get()}, connections=${connections.count}, streams=${streams.count}"
            }
            check(client.network.connections.isEmpty() && server.network.connections.isEmpty()) {
                "Noise preparation opened a connection"
            }
            val opened = client.newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"),
                server.peerId,
                *server.listenAddresses().toTypedArray()
            )
            try {
                check(opened.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS) >= 0)
                check(connections.await(10, TimeUnit.SECONDS) && streams.await(10, TimeUnit.SECONDS)) {
                    "The real dial did not invoke its connection and stream callbacks"
                }
                check(computations.get() > 0) { "The real dial did not invoke its computation observer" }
                println("NOISE_PREPARATION_CALLBACKS=0; real dial callbacks acknowledged")
            } finally {
                opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
            }
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
