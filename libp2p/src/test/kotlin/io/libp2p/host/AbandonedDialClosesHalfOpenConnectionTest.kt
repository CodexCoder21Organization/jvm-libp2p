package io.libp2p.host

import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A caller that gives up on a dial must be able to end it.
 *
 * The remote address accepts TCP and never speaks, so the dial stays inside multistream negotiation
 * until the negotiation time limit (10 s by default) ends it. UrlResolver's active query waits 2 s for
 * a stream to open and then abandons it; because neither `Host.newStream` nor `Network.connect` reacts
 * when the caller cancels the futures it was handed, every abandoned dial to such an address keeps
 * its socket for the full negotiation limit. Once every caller of a dial has cancelled, the dial's
 * connection must be closed at once rather than when the negotiation limit fires.
 */
class AbandonedDialClosesHalfOpenConnectionTest {

    @Test
    fun `cancelling the only newStream caller closes its half-open dial before the negotiation limit`() {
        val silent = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val accepted = LinkedBlockingQueue<Socket>()
        val acceptor = Thread {
            while (!silent.isClosed) {
                val socket = try { silent.accept() } catch (_: java.io.IOException) { break }
                accepted += socket
            }
        }.apply { isDaemon = true; start() }
        val client = createClient()
        try {
            val promise = client.newStream<PingController>(
                listOf(PING_PROTOCOL),
                PeerId.random(),
                Multiaddr("/ip4/127.0.0.1/tcp/${silent.localPort}")
            )
            val serverSide = accepted.poll(5, TimeUnit.SECONDS)
            assertThat(serverSide)
                .describedAs("the client must dial the silent listener on port ${silent.localPort}")
                .isNotNull()

            promise.controller.cancel(false)
            promise.stream.cancel(false)

            // Everything the client sends (its multistream header) is drained; only end-of-stream
            // proves the client closed the socket. The 2 s bound is a fifth of the negotiation limit,
            // so reaching end-of-stream here cannot be the negotiation limit closing the dial.
            val closedAfterMs = millisUntilEndOfStream(serverSide!!, CLOSE_BOUND_MS)
            assertThat(closedAfterMs)
                .describedAs(
                    "milliseconds until the client closed the abandoned dial's TCP connection " +
                        "(null = still open ${CLOSE_BOUND_MS} ms after every caller cancelled)"
                )
                .isNotNull()
        } finally {
            client.stop().get(10, TimeUnit.SECONDS)
            silent.close()
            accepted.forEach { runCatching { it.close() } }
            acceptor.join(5_000)
        }
    }

    private fun millisUntilEndOfStream(socket: Socket, boundMs: Long): Long? {
        val start = System.nanoTime()
        val result = CompletableFuture<Long?>()
        val reader = Thread {
            try {
                val input = socket.getInputStream()
                while (input.read() != -1) {
                    // Drain the client's multistream header; only end-of-stream matters.
                }
                result.complete((System.nanoTime() - start) / 1_000_000)
            } catch (_: java.io.IOException) {
                result.complete((System.nanoTime() - start) / 1_000_000)
            }
        }.apply { isDaemon = true; start() }
        return try {
            result.get(boundMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            null
        } finally {
            reader.interrupt()
        }
    }

    private fun createClient(): Host =
        host {
            protocols {
                add(Ping())
            }
        }.also { it.start().get(10, TimeUnit.SECONDS) }

    companion object {
        private const val PING_PROTOCOL = "/ipfs/ping/1.0.0"
        private const val CLOSE_BOUND_MS = 2_000L
    }
}
