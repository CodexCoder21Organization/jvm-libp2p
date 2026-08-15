package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkConnectionReadinessTest {
    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun stopHosts() {
        stopHostsPreservingFirstFailure(hosts)
    }

    @Test
    fun `same-dial subscribers wait until the public connection handler returns`() {
        val (server, _) = createHost()
        val (client, transport) = createHost()
        val handlerEntered = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        val handler = ConnectionHandler.create {
            handlerEntered.countDown()
            check(releaseHandler.await(10, TimeUnit.SECONDS))
        }
        client.addConnectionHandler(handler)

        try {
            val first = client.network.connect(server.peerId, server.listenAddresses().single())
            assertThat(handlerEntered.await(10, TimeUnit.SECONDS)).isTrue()
            val second = client.network.connect(server.peerId, server.listenAddresses().single())

            assertThat(transport.dialCount.get()).isEqualTo(1)
            assertThat(first.isDone).isFalse()
            assertThat(second.isDone).isFalse()

            releaseHandler.countDown()
            assertThat(second.get(10, TimeUnit.SECONDS)).isSameAs(first.get(10, TimeUnit.SECONDS))
        } finally {
            releaseHandler.countDown()
        }
    }

    @Test
    fun `every initialized address candidate reaches the public connection handler`() {
        val (server, _) = createHost(listenCount = 2)
        val (client, transport) = createHost()
        val handled = CountDownLatch(2)
        val handler = ConnectionHandler.create { handled.countDown() }
        client.addConnectionHandler(handler)

        client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
            .get(20, TimeUnit.SECONDS)

        assertThat(handled.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(transport.dialCount.get()).isEqualTo(2)
    }

    @Test
    fun `handler failure closes the candidate and a later connect dials fresh`() {
        val (server, _) = createHost()
        val (client, transport) = createHost()
        val failure = IllegalStateException("deliberate connection handler failure")
        val failingHandler = ConnectionHandler.create { throw failure }
        client.addConnectionHandler(failingHandler)

        val failedConnect = client.network.connect(server.peerId, server.listenAddresses().single())
        val error = assertThrows<ExecutionException> { failedConnect.get(10, TimeUnit.SECONDS) }
        assertThat(rootCause(error)).isSameAs(failure)
        transport.dialedConnections.single().closeFuture().get(10, TimeUnit.SECONDS)
        assertThat(transport.dialedConnections.single().closeFuture().isDone).isTrue()
        assertThat(client.network.connections).isEmpty()

        client.removeConnectionHandler(failingHandler)
        val fresh = client.network.connect(server.peerId, server.listenAddresses().single())
            .get(10, TimeUnit.SECONDS)
        assertThat(fresh.closeFuture().isDone).isFalse()
        assertThat(transport.dialCount.get()).isEqualTo(2)
    }

    @Test
    fun `a host rejects its own identity before transport work`() {
        val (host, transport) = createHost()

        val result = host.network.connect(host.peerId, host.listenAddresses().single())

        assertThatThrownBy { result.get(10, TimeUnit.SECONDS) }
            .hasRootCauseExactlyInstanceOf(Libp2pException::class.java)
            .hasRootCauseMessage("Cannot connect to peer ${host.peerId.toBase58()} because it is the local peer ID")
        assertThat(transport.dialCount.get()).isZero()
    }

    @Test
    fun `hosts with equal identities reject connection before transport work`() {
        val sharedIdentity = generateKeyPair(KeyType.ECDSA).first
        val (first, firstTransport) = createHost(identity = sharedIdentity)
        val (second, secondTransport) = createHost(identity = sharedIdentity)

        val result = first.network.connect(second.peerId, second.listenAddresses().single())

        assertThatThrownBy { result.get(10, TimeUnit.SECONDS) }
            .hasRootCauseExactlyInstanceOf(Libp2pException::class.java)
            .hasRootCauseMessage("Cannot connect to peer ${second.peerId.toBase58()} because it is the local peer ID")
        assertThat(firstTransport.dialCount.get()).isZero()
        assertThat(secondTransport.dialCount.get()).isZero()
    }

    private fun createHost(
        listenCount: Int = 1,
        identity: PrivKey? = null
    ): Pair<Host, RecordingTcpTransport> {
        lateinit var transport: RecordingTcpTransport
        val created = host {
            if (identity != null) {
                identity { factory = { identity } }
            }
            network {
                repeat(listenCount) { index -> listen("/ip4/127.0.0.${index + 1}/tcp/0") }
            }
            transports {
                add { upgrader -> RecordingTcpTransport(upgrader).also { transport = it } }
            }
        }
        hosts += created
        created.start().get(30, TimeUnit.SECONDS)
        return created to transport
    }

    private class RecordingTcpTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
        val dialCount = AtomicInteger()
        val dialedConnections = CopyOnWriteArrayList<Connection>()

        override fun dial(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Connection> {
            dialCount.incrementAndGet()
            return super.dial(
                addr,
                ConnectionHandler.create { connection ->
                    dialedConnections += connection
                    connHandler.handleConnection(connection)
                },
                preHandler
            )
        }
    }

    private fun rootCause(error: Throwable): Throwable =
        generateSequence(error) { it.cause }.last()
}
