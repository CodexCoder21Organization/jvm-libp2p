package io.libp2p.transport

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

class CompiledHostCallbacksTest {
    @Test
    fun `first ping does not load generated libp2p lambda classes`() {
        val output = Files.createTempFile("compiled-host-callbacks-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m",
            "-XX:+UseSerialGC",
            "-XX:ActiveProcessorCount=2",
            "-cp", System.getProperty("java.class.path"),
            "io.libp2p.transport.CompiledHostCallbacksProbe"
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            val completed = process.waitFor(30, TimeUnit.SECONDS)
            val text = Files.readString(output)
            print(text)
            assertTrue(completed, "Cold callback probe did not finish within 30 seconds:\n$text")
            assertEquals(0, process.exitValue(), text)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(
                    process.waitFor(5, TimeUnit.SECONDS),
                    "Cold callback probe child process was not reaped after forced termination"
                )
            }
            Files.deleteIfExists(output)
        }
    }
}

object CompiledHostCallbacksProbe {
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
        val recordingFile = Files.createTempFile("first-ping-callbacks-", ".jfr")
        var controller: PingController?
        var stream: io.libp2p.core.Stream? = null

        try {
            client.start().get(10, TimeUnit.SECONDS)
            server.start().get(10, TimeUnit.SECONDS)
            check(client.network.connections.isEmpty() && server.network.connections.isEmpty()) {
                "Host start must prepare the transport without opening a connection"
            }

            Recording().use { recording ->
                recording.enable("jdk.ClassLoad").withThreshold(Duration.ZERO).withStackTrace()
                recording.start()
                try {
                    val opened = client.newStream<PingController>(
                        listOf("/ipfs/ping/1.0.0"),
                        server.peerId,
                        *server.listenAddresses().toTypedArray()
                    )
                    controller = opened.controller.get(10, TimeUnit.SECONDS)
                    stream = opened.stream.get(10, TimeUnit.SECONDS)
                    val pingRoundTrip = controller!!.ping().get(10, TimeUnit.SECONDS)
                    check(pingRoundTrip >= 0) {
                        "The first Ping round trip returned invalid elapsed time $pingRoundTrip"
                    }
                } finally {
                    recording.stop()
                    recording.dump(recordingFile)
                }
            }

            val lambdaLoads = RecordingFile.readAllEvents(recordingFile)
                .asSequence()
                .filter { it.eventType.name == "jdk.ClassLoad" }
                .map { it.getClass("loadedClass").name }
                .filter { it.startsWith("io.libp2p.") && it.contains("\$\$Lambda") }
                .toList()
            val names = lambdaLoads.distinct().sorted()
            println("FIRST_PING_LIBP2P_LAMBDA_LOADS=${lambdaLoads.size}")
            names.forEach(::println)
            check(lambdaLoads.isEmpty()) {
                "First Ping loaded ${lambdaLoads.size} generated io.libp2p callback classes: $names"
            }
        } finally {
            try {
                stream?.close()?.get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    client.stop().get(10, TimeUnit.SECONDS)
                } finally {
                    try {
                        server.stop().get(10, TimeUnit.SECONDS)
                    } finally {
                        Files.deleteIfExists(recordingFile)
                    }
                }
            }
        }
    }
}
