package io.libp2p.security.noise

import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

class NoiseAlgorithmReadinessTest {
    @Test
    fun `first dial after start does not initialize Noise algorithm classes`() {
        val output = Files.createTempFile("host-transport-readiness-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m",
            "-cp", System.getProperty("java.class.path"),
            "io.libp2p.security.noise.NoiseAlgorithmReadinessProbe"
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cold readiness probe did not finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}

object NoiseAlgorithmReadinessProbe {
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
        val recordingFile = Files.createTempFile("first-dial-", ".jfr")
        try {
            client.start().get(10, TimeUnit.SECONDS)
            server.start().get(10, TimeUnit.SECONDS)
            check(client.network.connections.isEmpty() && server.network.connections.isEmpty()) {
                "Host start must prepare the transport without opening a connection"
            }
            Recording().use { recording ->
                recording.enable("jdk.ClassLoad").withThreshold(Duration.ZERO).withStackTrace()
                recording.start()
                val opened = client.newStream<PingController>(
                    listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray()
                )
                try {
                    val controller = opened.controller.get(10, TimeUnit.SECONDS)
                    recording.stop()
                    recording.dump(recordingFile)
                    check(controller.ping().get(10, TimeUnit.SECONDS) >= 0)
                } finally {
                    opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                }
            }
            val loads = RecordingFile.readAllEvents(recordingFile).filter { it.eventType.name == "jdk.ClassLoad" }
                .filter {
                    val name = it.getClass("loadedClass").name
                    name.startsWith("com.southernstorm.noise.crypto.") ||
                        name == "com.southernstorm.noise.protocol.HandshakeState" ||
                        name == "com.southernstorm.noise.protocol.Pattern" ||
                        name == "com.southernstorm.noise.protocol.SymmetricState" ||
                        name == "com.southernstorm.noise.protocol.ChaChaPolyCipherState" ||
                        name == "com.southernstorm.noise.protocol.DHStateHybrid"
                }
            val names = loads.map { it.getClass("loadedClass").name }.distinct().sorted()
            println("FIRST_DIAL_NOISE_ALGORITHM_LOADS=${loads.size}\n${names.joinToString("\n")}")
            check(loads.isEmpty()) {
                "Host.start returned before Noise algorithm initialization: ${loads.size} first-dial class-load events: $names"
            }
        } finally {
            try { client.stop().get(10, TimeUnit.SECONDS) } finally { server.stop().get(10, TimeUnit.SECONDS) }
            Files.deleteIfExists(recordingFile)
        }
    }
}
