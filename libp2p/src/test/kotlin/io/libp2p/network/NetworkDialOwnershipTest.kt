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
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS

class NetworkDialOwnershipTest {
    @Test
    fun `last subscriber cancels the physical dial and a retry starts fresh`() {
        withOwnedDialHosts { client, server, transport ->
            val address = server.listenAddresses().first()
            val first = client.network.connect(server.peerId, address)
            val second = client.network.connect(server.peerId, address)
            val physical = transport.results.single()

            first.cancel(true)
            assertThat(physical.isCancelled).isFalse()
            assertThat(second.isDone).isFalse()
            second.cancel(true)
            assertThat(physical.isCancelled).isTrue()

            val retry = client.network.connect(server.peerId, address)
            assertThat(transport.results).hasSize(2)
            transport.gates.last().complete(Unit)
            val connection = retry.get(10, SECONDS)
            assertThat(connection.secureSession().remoteId).isEqualTo(server.peerId)
            assertThat(connection.closeFuture().isDone).isFalse()
        }
    }

    @Test
    fun `a connection completing after its last subscriber leaves is closed`() {
        withOwnedDialHosts(declineCancellation = true) { client, server, transport ->
            val connect = client.network.connect(server.peerId, server.listenAddresses().first())
            val physical = transport.results.single()
            connect.cancel(true)
            assertThat(physical.isDone).isFalse()

            transport.gates.single().complete(Unit)
            val unowned = physical.get(10, SECONDS)
            unowned.closeFuture().get(10, SECONDS)
            assertThat(unowned.closeFuture().isCompletedExceptionally).isFalse()
        }
    }

    @Test
    fun `overlapping address callers keep the shared dial until both leave`() {
        withOwnedDialHosts { client, server, transport ->
            val address = server.listenAddresses().first()
            val unused = Multiaddr("/ip4/127.0.0.1/tcp/0")
            val both = client.network.connect(server.peerId, unused, address)
            val shared = client.network.connect(server.peerId, address)
            assertThat(transport.results).hasSize(2)

            both.cancel(true)
            assertThat(transport.results[0].isCancelled).isTrue()
            assertThat(transport.results[1].isCancelled).isFalse()
            transport.gates[1].complete(Unit)
            val connection = shared.get(10, SECONDS)
            assertThat(connection.secureSession().remoteId).isEqualTo(server.peerId)
            assertThat(connection.closeFuture().isDone).isFalse()
        }
    }

    @Test
    fun `one caller succeeding keeps another callers shared dial and closes its surplus result`() {
        withOwnedDialHosts { client, server, transport ->
            val firstAddress = server.listenAddresses()[0]
            val secondAddress = server.listenAddresses()[1]
            val both = client.network.connect(server.peerId, firstAddress, secondAddress)
            val shared = client.network.connect(server.peerId, secondAddress)
            assertThat(transport.results).hasSize(2)

            transport.gates[0].complete(Unit)
            val survivor = both.get(10, SECONDS)
            assertThat(transport.results[1].isCancelled).isFalse()
            assertThat(shared.isDone).isFalse()
            transport.gates[1].complete(Unit)
            assertThat(shared.get(10, SECONDS)).isSameAs(survivor)
            val surplus = transport.results[1].get(10, SECONDS)
            surplus.closeFuture().get(10, SECONDS)
            assertThat(survivor.closeFuture().isDone).isFalse()
        }
    }

    @Test
    fun `a cancellation callback can immediately subscribe to a fresh dial`() {
        withOwnedDialHosts { client, server, transport ->
            val address = server.listenAddresses().first()
            val first = client.network.connect(server.peerId, address)
            // Registered after Network's cleanup callback: CompletableFuture runs the most
            // recently registered callback first, so this also checks callback re-entry.
            val retried = first.handle { _, _ -> client.network.connect(server.peerId, address) }
            first.cancel(true)
            val retry = retried.get(5, SECONDS)
            assertThat(transport.results).hasSize(2)
            assertThat(transport.results[0].isCancelled).isTrue()
            transport.gates.last().complete(Unit)
            assertThat(retry.get(10, SECONDS).secureSession().remoteId).isEqualTo(server.peerId)
        }
    }
}

private fun withOwnedDialHosts(
    declineCancellation: Boolean = false,
    scenario: (Host, Host, OwnedDialTcpTransport) -> Unit
) {
    val server = host {
        network {
            listen("/ip4/127.0.0.1/tcp/0")
            listen("/ip4/127.0.0.2/tcp/0")
        }
        protocols { +Ping() }
    }
    lateinit var transport: OwnedDialTcpTransport
    val client = host {
        transports {
            add { upgrader ->
                OwnedDialTcpTransport(upgrader, declineCancellation).also { transport = it }
            }
        }
        protocols { +Ping() }
    }
    try {
        CompletableFuture.allOf(server.start(), client.start()).get(10, SECONDS)
        scenario(client, server, transport)
    } finally {
        transport.gates.forEach { it.completeExceptionally(IllegalStateException("Closing an unused test dial")) }
        CompletableFuture.allOf(client.stop(), server.stop()).get(10, SECONDS)
    }
}

private class OwnedDialTcpTransport(
    upgrader: ConnectionUpgrader,
    private val declineCancellation: Boolean
) : TcpTransport(upgrader) {
    val gates = CopyOnWriteArrayList<CompletableFuture<Unit>>()
    val results = CopyOnWriteArrayList<CompletableFuture<Connection>>()

    override fun dial(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Connection> {
        val gate = CompletableFuture<Unit>()
        val result = object : CompletableFuture<Connection>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
                if (declineCancellation) false else super.cancel(mayInterruptIfRunning)
        }
        gates += gate
        results += result
        gate.thenCompose { super.dial(addr, connHandler, preHandler) }
            .whenComplete { connection, failure ->
                if (failure == null) {
                    if (!result.complete(connection)) connection.close()
                } else {
                    result.completeExceptionally(failure)
                }
            }
        return result
    }
}
