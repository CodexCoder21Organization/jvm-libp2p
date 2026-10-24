package io.libp2p.security.noise

import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.security.SecureChannel
import io.libp2p.protocol.Ping
import io.libp2p.transport.ws.WsTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class NoiseBindingReadinessTest {
    @Test
    fun `sole cold host with delegating Noise binding prepares before first stream`() = bindingProbe("wrapped")

    @Test
    fun `sole cold WebSocket host prepares before first stream`() = bindingProbe("ws")

    @Test
    fun `sole cold WebSocket host with delegating Noise binding prepares before first stream`() = bindingProbe("wrapped-ws")
}

private fun bindingProbe(mode: String) {
    // Prepare the real remote host before launching the subject whose first use is measured.
    val peer = host {
        identity { random(KeyType.ED25519) }
        muxers { +StreamMuxerProtocol.getYamux() }
        protocols { +Ping() }
        if (mode == "wrapped" || mode == "wrapped-ws") {
            secureChannels {
                add { key, _ ->
                    val noise = NoiseXXSecureChannel(key)
                    object : SecureChannel by noise {}
                }
            }
        }
        if (mode == "ws" || mode == "wrapped-ws") {
            transports { +::WsTransport }
            network { listen("/ip4/127.0.0.1/tcp/0/ws") }
        } else {
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
    }
    try {
        peer.start().get(10, TimeUnit.SECONDS)
        check(peer.network.connections.isEmpty()) {
            "Host start must prepare the transport without opening a connection"
        }
        val output = Files.createTempFile("noise-binding-readiness-", ".log")
        val agent = output.resolveSibling(output.fileName.toString() + ".agent.jar")
        try {
            writeNoiseClassLoadAgent(agent)
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-Xmx128m",
                "-javaagent:$agent",
                "-Dio.netty.eventLoopThreads=1",
                "-cp",
                System.getProperty("java.class.path"),
                "io.libp2p.security.noise.NoiseAlgorithmReadinessProbe",
                mode,
                peer.listenAddresses().single().toString()
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue(awaitNoiseProbe(process, output), "Cold binding readiness probe did not finish")
                val text = Files.readString(output)
                print(text)
                assertEquals(0, process.exitValue(), text)
            } finally {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            try {
                Files.deleteIfExists(agent)
            } finally {
                Files.deleteIfExists(output)
            }
        }
    } finally {
        peer.stop().get(5, TimeUnit.SECONDS)
    }
}
