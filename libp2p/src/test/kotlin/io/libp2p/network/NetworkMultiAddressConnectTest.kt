package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Ping
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A peer is normally reachable at several addresses, and [io.libp2p.core.Network.connect] is
 * handed all of them at once. It dials every address in parallel and hands the caller whichever
 * dial finishes first. The dials that finish afterwards also produce a fully upgraded connection,
 * and those connections belong to nobody: the caller never sees them, so nothing ever closes them.
 *
 * Each one keeps a live TCP socket, a Netty pipeline and a muxer session for as long as the
 * process runs, so a long-lived process that dials a large, churning peer population retains one
 * socket per surplus address per peer it has ever contacted.
 */
class NetworkMultiAddressConnectTest {
    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun stopHosts() {
        hosts.asReversed().forEach { it.stop().get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun `connecting to a peer over several of its addresses leaves exactly one connection open`() {
        val server = createServer(listenAddressCount = 3)
        val serverAddresses = server.listenAddresses()
        assertThat(serverAddresses)
            .describedAs("the server must advertise several addresses for this scenario to exist")
            .hasSize(3)

        val (client, transport) = createRecordingClient()

        val established = client.network
            .connect(server.peerId, *serverAddresses.toTypedArray())
            .get(30, TimeUnit.SECONDS)
        assertThat(established.secureSession().remoteId).isEqualTo(server.peerId)

        // Every address was dialled, and every dial ran to completion. Waiting for all of them
        // removes any dependence on which dial happens to win the race on this machine.
        assertThat(transport.dials).hasSize(3)
        CompletableFuture.allOf(*transport.dials.toTypedArray()).get(30, TimeUnit.SECONDS)
        val dialledConnections = transport.dials.map { it.get() }
        assertThat(dialledConnections).hasSize(3)

        assertThat(settledConnectionCount(client))
            .describedAs(
                "connections the client holds to the server after dialling its 3 addresses; " +
                    "the 2 dials that lost the race must be closed, not retained"
            )
            .isEqualTo(1)
        assertThat(settledConnectionCount(server))
            .describedAs("connections the server holds from the client")
            .isEqualTo(1)

        assertThat(dialledConnections.count { !it.closeFuture().isDone })
            .describedAs("dialled connections still open")
            .isEqualTo(1)
        assertThat(established.closeFuture().isDone)
            .describedAs("the connection handed to the caller must be the one that stays open")
            .isFalse()
    }

    /**
     * Reports the connection count once it has stopped changing. A losing dial is closed
     * asynchronously, so the count is read after it settles rather than at an arbitrary instant;
     * a count that never settles at the expected value still fails the assertion it feeds.
     */
    private fun settledConnectionCount(host: Host): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var count = host.network.connections.size
        while (count != 1 && System.nanoTime() < deadline) {
            Thread.sleep(25)
            count = host.network.connections.size
        }
        return count
    }

    /**
     * Listens on [listenAddressCount] distinct loopback addresses, which is what gives the peer
     * several advertised addresses. Distinct loopback IPs are used rather than repeating
     * `/ip4/127.0.0.1/tcp/0`, because a listen address is requested by its multiaddress and the
     * same string requested twice is one request.
     */
    private fun createServer(listenAddressCount: Int): Host =
        host {
            network {
                (1..listenAddressCount).forEach { listen("/ip4/127.0.0.$it/tcp/0") }
            }
            protocols {
                add(Ping())
            }
        }.also {
            hosts += it
            it.start().get(30, TimeUnit.SECONDS)
        }

    private fun createRecordingClient(): Pair<Host, RecordingTcpTransport> {
        lateinit var recordingTransport: RecordingTcpTransport
        val client = host {
            transports {
                add { upgrader -> RecordingTcpTransport(upgrader).also { recordingTransport = it } }
            }
            protocols {
                add(Ping())
            }
        }
        hosts += client
        client.start().get(30, TimeUnit.SECONDS)
        return client to recordingTransport
    }

    /** Keeps every dial's result so the test can wait for all of them, not just the winning one. */
    private class RecordingTcpTransport(
        upgrader: ConnectionUpgrader
    ) : TcpTransport(upgrader) {
        val dials = CopyOnWriteArrayList<CompletableFuture<Connection>>()

        override fun dial(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Connection> =
            super.dial(addr, connHandler, preHandler).also { dials += it }
    }
}
