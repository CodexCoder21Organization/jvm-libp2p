package io.libp2p.network

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.ref.Reference
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ConnectionSelectionMemoryTest {
    @Test
    fun `disconnected peer history does not retain selection records`() {
        val client = memoryTestHost(listen = true)
        val incoming = LinkedBlockingQueue<Connection>()
        client.addConnectionHandler(ConnectionHandler.create { incoming.add(it) })
        try {
            client.start().get(10, TimeUnit.SECONDS)
            // Serial deliberately: one surviving network accumulates disconnected history,
            // while each peer's entire live working set is released before the next peer.
            repeat(10) { connectAndReleaseMemoryPeer(client, incoming) }
            val before = liveSelectionRecords()
            repeat(1000) { connectAndReleaseMemoryPeer(client, incoming) }
            val middle = liveSelectionRecords()
            repeat(1000) { connectAndReleaseMemoryPeer(client, incoming) }
            val after = liveSelectionRecords()
            println("Live selection records after warmup / 1000 / 2000 peers: $before / $middle / $after")
            assertThat(middle).describedAs("retained records after 1000 disconnected identities").isEqualTo(before)
            assertThat(after).describedAs("retained records after 2000 disconnected identities").isEqualTo(before)
            assertThat(client.network.connections).isEmpty()
        } finally {
            // The measured owner must remain reachable throughout both live-heap measurements.
            Reference.reachabilityFence(client)
            client.stop().get(10, TimeUnit.SECONDS)
        }
    }
}

private fun memoryTestHost(listen: Boolean = false): Host = host {
    if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
}

private fun connectAndReleaseMemoryPeer(client: Host, incoming: LinkedBlockingQueue<Connection>) {
    val peer = memoryTestHost()
    try {
        peer.start().get(10, TimeUnit.SECONDS)
        val outgoing = peer.network.connect(client.peerId, *client.listenAddresses().toTypedArray()).get(10, TimeUnit.SECONDS)
        val connection = checkNotNull(incoming.poll(10, TimeUnit.SECONDS)) { "Receiving network did not publish the connected peer" }
        assertThat(connection.secureSession().remoteId).isEqualTo(peer.peerId)
        connection.close().get(10, TimeUnit.SECONDS)
        listOf(connection, outgoing).forEach {
            it.closeFuture().get(10, TimeUnit.SECONDS)
            (it as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
        }
        assertThat(client.network.connections).isEmpty()
    } finally {
        peer.stop().get(10, TimeUnit.SECONDS)
        peer.network.transports.forEach {
            assertThat(it.activeConnections).isZero()
            assertThat(it.activeListeners).isZero()
        }
    }
}

private fun liveSelectionRecords(): Long {
    val process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
        ProcessHandle.current().pid().toString(),
        "GC.class_histogram"
    ).redirectErrorStream(true).start()
    try {
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
            .get(30, TimeUnit.SECONDS)
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).describedAs("own-JVM live histogram completed").isTrue()
        assertThat(process.exitValue()).describedAs(output).isZero()
        val rows = output.lineSequence().map { line -> line.trim().split(' ').filter { it.isNotEmpty() } }
        return rows.singleOrNull { it.size >= 4 && it[3] == "io.libp2p.network.PeerSelectionSupport" }
            ?.get(1)?.toLong() ?: 0L
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Own-JVM histogram process did not stop" }
        }
    }
}
