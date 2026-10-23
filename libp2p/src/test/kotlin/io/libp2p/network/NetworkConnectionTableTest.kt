package io.libp2p.network

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The connection table [NetworkImpl.connections] is what `close()`, `disconnect()` and
 * `findActiveConnection()` all work from, so a live connection that is missing from it can
 * never be closed by its owner and can never be reused by the next dial to the same peer.
 *
 * `createHookedConnHandler` used to broadcast to the application's connection handlers
 * *before* its own bookkeeping handler, and [io.libp2p.etc.BroadcastConnectionHandler]
 * dispatches with a bare `forEach` that stops at the first handler which throws. An
 * application connection handler — a public extension point, registered via
 * [Host.addConnectionHandler] — that failed therefore stopped the broadcast before
 * `connections += conn` and before the `closeFuture` removal was ever registered. The
 * connection was already fully established at that point (secure session and muxer both
 * attached; `handleConnection` is the last step of
 * [io.libp2p.transport.implementation.ConnectionBuilder.initChannel]) and the socket stayed
 * open, so each `connect()` opened another one that nothing tracked and nothing closed.
 *
 * These tests pin the invariant directly: every live transport channel is matched by an
 * entry in the connection table, and closing everything in the connection table therefore
 * releases every socket.
 */
class NetworkConnectionTableTest {

    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun stopHosts() {
        hosts.asReversed().forEach { it.stop().get(10, TimeUnit.SECONDS) }
    }

    private fun createHost(privKey: PrivKey): Host =
        host {
            identity { factory = { privKey } }
            transports { add(::TcpTransport) }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { add(Ping()) }
        }.also { hosts += it }

    private fun failingConnectionHandler() = ConnectionHandler.create {
        throw IllegalStateException(HANDLER_FAILURE_MESSAGE)
    }

    @Test
    fun `a dialling host whose connection handler fails must still track every live connection`() {
        val server = createHost(generateKeyPair(KeyType.ECDSA).first)
        server.start().get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val serverAddress = server.listenAddresses().single()

        val client = createHost(generateKeyPair(KeyType.ECDSA).first)
        client.addConnectionHandler(failingConnectionHandler())
        client.start().get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val clientTransport = client.network.transports.single()

        // Every attempt is driven to completion before the next one starts, so the number of
        // dials the host has performed is exact rather than timing dependent.
        repeat(DIAL_ATTEMPTS) {
            runCatching { client.network.connect(server.peerId, serverAddress).get(DIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }

        assertThat(clientTransport.activeConnections)
            .describedAs(
                "After $DIAL_ATTEMPTS dials to one peer the dialling host holds " +
                    "${clientTransport.activeConnections} live transport channels but its connection table " +
                    "(Network.connections) lists ${client.network.connections.size}. Every live connection must " +
                    "appear in the connection table: a connection missing from it cannot be closed by " +
                    "Network.disconnect / Network.close and cannot be reused by the next connect(), so each " +
                    "further dial to the same peer strands another established socket. The application " +
                    "connection handler registered by this test fails with " +
                    "\"$HANDLER_FAILURE_MESSAGE\"; that failure must not cost the network its own bookkeeping."
            )
            .isEqualTo(client.network.connections.size)

        // Closing everything the connection table lists must release every socket the host opened.
        val closed = client.network.connections.toList().map { it.close() }
        CompletableFuture.allOf(*closed.toTypedArray()).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertThat(clientTransport.activeConnections)
            .describedAs(
                "Closing every connection the connection table lists left " +
                    "${clientTransport.activeConnections} live transport channels behind on the dialling host. " +
                    "Those sockets are unreachable through the Network API and stay open for the lifetime of " +
                    "the host."
            )
            .isZero()
    }

    @Test
    fun `a listening host whose connection handler fails must still track the accepted connection`() {
        val server = createHost(generateKeyPair(KeyType.ECDSA).first)
        server.addConnectionHandler(failingConnectionHandler())
        server.start().get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val serverAddress = server.listenAddresses().single()
        val serverTransport = server.network.transports.single()

        val client = createHost(generateKeyPair(KeyType.ECDSA).first)
        client.start().get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        client.network.connect(server.peerId, serverAddress).get(DIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // The accepted channel is registered by the transport before negotiation begins, so it is
        // already present; wait for the connection table to catch up to it rather than sampling a
        // half-completed accept.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TABLE_SETTLE_TIMEOUT_SECONDS)
        while (server.network.connections.isEmpty() &&
            serverTransport.activeConnections > 0 &&
            System.nanoTime() < deadline
        ) {
            Thread.onSpinWait()
        }

        assertThat(server.network.connections.size)
            .describedAs(
                "The listening host holds ${serverTransport.activeConnections} live transport channels but its " +
                    "connection table (Network.connections) lists ${server.network.connections.size}. The " +
                    "application connection handler registered by this test fails with " +
                    "\"$HANDLER_FAILURE_MESSAGE\"; an accepted connection that is already fully established " +
                    "must still be recorded so Network.close and Network.disconnect can reach it."
            )
            .isEqualTo(serverTransport.activeConnections)
    }

    companion object {
        private const val HANDLER_FAILURE_MESSAGE = "application connection handler rejected this connection"
        private const val DIAL_ATTEMPTS = 25
        private const val START_TIMEOUT_SECONDS = 10L
        private const val DIAL_TIMEOUT_SECONDS = 10L
        private const val CLOSE_TIMEOUT_SECONDS = 10L
        private const val TABLE_SETTLE_TIMEOUT_SECONDS = 10L
    }
}
