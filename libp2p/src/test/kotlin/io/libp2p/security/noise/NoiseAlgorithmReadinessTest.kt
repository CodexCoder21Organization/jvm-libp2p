package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.security.SecureChannel
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.transport.ws.WsTransport
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.Security
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NoiseAlgorithmReadinessTest {
    @Test
    fun `first dial after start does not initialize Noise algorithm classes`() {
        val peer = readinessHost("direct")
        try {
            peer.start().get(10, TimeUnit.SECONDS)
            check(peer.network.connections.isEmpty()) {
                "Host start must prepare the transport without opening a connection"
            }
            val output = Files.createTempFile("host-transport-readiness-", ".log")
            try {
                val process = ProcessBuilder(
                    File(System.getProperty("java.home"), "bin/java").absolutePath,
                    "-Xmx128m",
                    "-cp",
                    System.getProperty("java.class.path"),
                    "io.libp2p.security.noise.NoiseAlgorithmReadinessProbe",
                    "direct",
                    peer.listenAddresses().single().toString()
                ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
                try {
                    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cold readiness probe did not finish")
                    val text = Files.readString(output)
                    print(text)
                    assertEquals(0, process.exitValue(), text)
                } finally {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                }
            } finally {
                Files.deleteIfExists(output)
            }
        } finally {
            peer.stop().get(5, TimeUnit.SECONDS)
        }
    }
}

object NoiseAlgorithmReadinessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 2) { "Readiness probe requires a mode and an already listening peer endpoint; received ${args.toList()}" }
        val mode = args[0]
        val address = Multiaddr(args[1])
        var client: Host? = null
        val provider = NoisePreparationProvider()
        val recordingFile = Files.createTempFile("first-dial-", ".jfr")
        try {
            check(Security.insertProviderAt(provider, 1) == 1) { "Counting SHA-256 provider was not installed first" }
            val local = readinessHost(mode)
            client = local
            check(provider.initializations.get() == 1) { "Host returned before the real SHA-256 provider's first use" }
            check(provider.allocations.get() == 2) { "Host did not prepare both Noise roles" }
            check(provider.destroyed.get() == 2 && provider.duplicateDestructions.get() == 0) {
                "Temporary role digests were not destroyed exactly once before readiness"
            }
            check(!provider.onNettyExecutor.get()) { "Noise preparation used a Netty executor before readiness" }
            val initializedBeforeStream = provider.initializations.get()
            local.start().get(10, TimeUnit.SECONDS)
            check(local.network.connections.isEmpty()) {
                "Host start must prepare the transport without opening a connection"
            }
            Recording().use { recording ->
                recording.enable("jdk.ClassLoad").withThreshold(Duration.ZERO).withStackTrace()
                recording.start()
                val opened = local.newStream<PingController>(
                    listOf("/ipfs/ping/1.0.0"),
                    requireNotNull(address.getPeerId()),
                    address
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
            check(provider.initializations.get() == initializedBeforeStream) {
                "First real stream initialized the SHA-256 provider after host readiness"
            }
            println("PROVIDER_INITIALIZATIONS_BEFORE_READY=$initializedBeforeStream TEMPORARY_DIGESTS_DESTROYED=2")
            val names = loads.map { it.getClass("loadedClass").name }.distinct().sorted()
            println("FIRST_DIAL_NOISE_ALGORITHM_LOADS=${loads.size}\n${names.joinToString("\n")}")
            check(loads.isEmpty()) {
                "Host.start returned before Noise algorithm initialization: ${loads.size} first-dial class-load events: $names"
            }
        } finally {
            try {
                client?.stop()?.get(10, TimeUnit.SECONDS)
            } finally {
                Security.removeProvider(provider.name)
                Files.deleteIfExists(recordingFile)
            }
        }
    }
}

private fun readinessHost(mode: String): Host = host {
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

object NoiseReadinessPeer {
    @JvmStatic
    fun main(args: Array<String>) {
        val server = readinessHost(args.single())
        try {
            server.start().get(10, TimeUnit.SECONDS)
            check(server.network.connections.isEmpty()) {
                "Host start must prepare the transport without opening a connection"
            }
            println("PEER=${server.listenAddresses().single()}")
            System.out.flush()
            CompletableFuture.supplyAsync { System.`in`.read() }.get(20, TimeUnit.SECONDS)
        } finally {
            server.stop().get(10, TimeUnit.SECONDS)
        }
    }
}
