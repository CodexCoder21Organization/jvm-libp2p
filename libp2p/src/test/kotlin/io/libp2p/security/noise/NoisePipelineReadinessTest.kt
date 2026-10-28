package io.libp2p.security.noise

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
import java.util.concurrent.TimeUnit

class NoisePipelineReadinessTest {
    @Test
    fun `first dial after start does not initialize Noise callback or session classes`() {
        val output = Files.createTempFile("host-transport-readiness-", ".log")
        val agent = output.resolveSibling(output.fileName.toString() + ".agent.jar")
        writeNoiseClassLoadAgent(agent)
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m",
            "-javaagent:$agent",
            "-cp", System.getProperty("java.class.path"),
            "io.libp2p.security.noise.NoisePipelineReadinessProbe"
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cold readiness probe did not finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            try { Files.deleteIfExists(agent) } finally { Files.deleteIfExists(output) }
        }
    }
}

object NoisePipelineReadinessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val client = host {
            identity { random(KeyType.ED25519) }
            muxers { +StreamMuxerProtocol.getYamux() }
            protocols { +Ping() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val server = host {
            identity { random(KeyType.ED25519) }
            muxers { +StreamMuxerProtocol.getYamux() }
            protocols { +Ping() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val observed: List<String>
        try {
            client.start().get(10, TimeUnit.SECONDS)
            server.start().get(10, TimeUnit.SECONDS)
            check(client.network.connections.isEmpty() && server.network.connections.isEmpty()) {
                "Host start must prepare the transport without opening a connection"
            }
            NoiseClassLoadObserver.begin()
            val opened = client.newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray()
            )
            try {
                val controller = opened.controller.get(10, TimeUnit.SECONDS)
                observed = NoiseClassLoadObserver.end()
                check(controller.ping().get(10, TimeUnit.SECONDS) >= 0)
            } finally {
                opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
            }
            val loads = requireNotNull(observed).filter { it.startsWith("io.libp2p.security.noise.") }
            val names = loads.distinct().sorted()
            println("FIRST_DIAL_NOISE_PIPELINE_LOADS=${loads.size}\n${names.joinToString("\n")}")
            check(loads.isEmpty()) {
                "Host.start returned before Noise callback/session initialization: ${loads.size} first-dial class-load events: $names"
            }
        } finally {
            try { client.stop().get(10, TimeUnit.SECONDS) } finally { server.stop().get(10, TimeUnit.SECONDS) }
            NoiseClassLoadObserver.close()
        }
    }
}
