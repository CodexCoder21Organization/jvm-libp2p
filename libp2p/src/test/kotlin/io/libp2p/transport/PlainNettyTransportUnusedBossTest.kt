package io.libp2p.transport

import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PlainNettyTransportUnusedBossTest {
    @Test
    fun `closing a client connection does not start an unused accept loop`() {
        val relay = host {
            identity { random(KeyType.ED25519) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val client = host { identity { random(KeyType.ED25519) } }
        val recordingFile = Files.createTempFile("libp2p-client-close-", ".jfr")
        try {
            CompletableFuture.allOf(relay.start(), client.start()).get(5, TimeUnit.SECONDS)
            client.network.connect(relay.peerId, relay.listenAddresses().single()).get(5, TimeUnit.SECONDS)
            Recording().use { recording ->
                recording.enable("jdk.ThreadStart").withoutStackTrace()
                recording.start()
                client.stop().get(5, TimeUnit.SECONDS)
                recording.stop()
                recording.dump(recordingFile)
                val acceptThreads = RecordingFile.readAllEvents(recordingFile)
                    .filter { it.eventType.name == "jdk.ThreadStart" }
                    .map { it.getThread("thread").javaName }
                    .filter { it.startsWith("libp2p-nio-boss-") }
                assertTrue(
                    acceptThreads.isEmpty(),
                    "Closing a client-only host must not create an accept loop, but started $acceptThreads"
                )
            }
        } finally {
            try {
                CompletableFuture.allOf(client.stop(), relay.stop()).get(5, TimeUnit.SECONDS)
            } finally {
                Files.deleteIfExists(recordingFile)
            }
        }
    }
}
