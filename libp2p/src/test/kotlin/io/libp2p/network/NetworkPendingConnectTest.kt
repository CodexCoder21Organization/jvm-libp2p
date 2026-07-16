package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NetworkPendingConnectTest {
    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun stopHosts() {
        hosts.asReversed().forEach { it.stop().get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun `concurrent connect and newStream calls share one pending transport dial`() {
        val server = createServer()
        val blockedDial = CompletableFuture<Unit>()
        val (client, transport) = createClient(blockedDial)
        val serverAddress = server.listenAddresses().single()
        val executor = Executors.newFixedThreadPool(CALLER_COUNT)
        val start = CountDownLatch(1)
        var dialReleased = false

        try {
            val invocations = (0 until CALLER_COUNT).map { index ->
                CompletableFuture.supplyAsync<CompletableFuture<*>>(
                    {
                        start.await()
                        if (index % 2 == 0) {
                            client.network.connect(server.peerId, serverAddress)
                        } else {
                            client.newStream<PingController>(
                                listOf(PING_PROTOCOL),
                                server.peerId,
                                serverAddress
                            ).controller
                        }
                    },
                    executor
                )
            }

            start.countDown()
            val pendingOperations = invocations.map { it.get(5, TimeUnit.SECONDS) }

            assertThat(transport.dialCount.get())
                .describedAs("transport dials while all calls to the same peer are pending")
                .isEqualTo(1)

            blockedDial.complete(Unit)
            dialReleased = true
            CompletableFuture.allOf(*pendingOperations.toTypedArray()).get(10, TimeUnit.SECONDS)

            assertThat(client.network.connections).hasSize(1)
            assertThat(server.network.connections).hasSize(1)
        } finally {
            if (!dialReleased) {
                blockedDial.completeExceptionally(IllegalStateException("Releasing a blocked test dial after assertion failure"))
            }
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed shared dial is removed before a later connect`() {
        val server = createServer()
        val failedDial = CompletableFuture<Unit>()
        val (client, transport) = createClient(failedDial)
        val serverAddress = server.listenAddresses().single()
        val pendingConnects = List(CALLER_COUNT) {
            client.network.connect(server.peerId, serverAddress)
        }

        assertThat(transport.dialCount.get())
            .describedAs("transport dials while all calls to the same peer are pending")
            .isEqualTo(1)

        failedDial.completeExceptionally(IllegalStateException("Deliberate first dial failure"))
        assertThat(pendingConnects.all { it.isCompletedExceptionally }).isTrue()

        transport.dialGate.set(CompletableFuture.completedFuture(Unit))
        val recoveredConnection = client.network.connect(server.peerId, serverAddress).get(10, TimeUnit.SECONDS)

        assertThat(recoveredConnection.secureSession().remoteId).isEqualTo(server.peerId)
        assertThat(transport.dialCount.get()).isEqualTo(2)
    }

    @Test
    fun `cancelled shared dial is removed before a later connect`() {
        val server = createServer()
        val blockedDial = CompletableFuture<Unit>()
        val (client, transport) = createClient(blockedDial)
        val serverAddress = server.listenAddresses().single()

        val cancelledConnect = client.network.connect(server.peerId, serverAddress)
        assertThat(transport.dialCount.get()).isEqualTo(1)
        assertThat(cancelledConnect.cancel(true)).isTrue()

        transport.dialGate.set(CompletableFuture.completedFuture(Unit))
        val recoveredConnection = client.network.connect(server.peerId, serverAddress).get(10, TimeUnit.SECONDS)

        assertThat(recoveredConnection.secureSession().remoteId).isEqualTo(server.peerId)
        assertThat(transport.dialCount.get()).isEqualTo(2)
    }

    @Test
    fun `one connect still dials all supplied addresses in parallel`() {
        val server = createServer()
        val blockedDial = CompletableFuture<Unit>()
        val (client, transport) = createClient(blockedDial)
        val liveServerAddress = server.listenAddresses().single()
        val unavailableLocalAddress = Multiaddr("/ip4/127.0.0.1/tcp/0")

        val pendingConnect = client.network.connect(
            server.peerId,
            liveServerAddress,
            unavailableLocalAddress
        )

        assertThat(transport.dialCount.get()).isEqualTo(2)
        blockedDial.complete(Unit)
        assertThat(pendingConnect.get(10, TimeUnit.SECONDS).secureSession().remoteId)
            .isEqualTo(server.peerId)
    }

    @Test
    fun `connects to different peers start independent transport dials`() {
        val firstServer = createServer()
        val secondServer = createServer()
        val blockedDial = CompletableFuture<Unit>()
        val (client, transport) = createClient(blockedDial)

        val firstConnect = client.network.connect(
            firstServer.peerId,
            firstServer.listenAddresses().single()
        )
        val secondConnect = client.network.connect(
            secondServer.peerId,
            secondServer.listenAddresses().single()
        )

        assertThat(transport.dialCount.get()).isEqualTo(2)
        blockedDial.complete(Unit)
        assertThat(
            listOf(
                firstConnect.get(10, TimeUnit.SECONDS).secureSession().remoteId,
                secondConnect.get(10, TimeUnit.SECONDS).secureSession().remoteId
            )
        ).containsExactlyInAnyOrder(firstServer.peerId, secondServer.peerId)
    }

    private fun createServer(): Host =
        host {
            network {
                listen("/ip4/127.0.0.1/tcp/0")
            }
            protocols {
                add(Ping())
            }
        }.also {
            hosts += it
            it.start().get(10, TimeUnit.SECONDS)
        }

    private fun createClient(initialDialGate: CompletableFuture<Unit>): Pair<Host, GatedCountingTcpTransport> {
        lateinit var countingTransport: GatedCountingTcpTransport
        val client = host {
            transports {
                add { upgrader ->
                    GatedCountingTcpTransport(upgrader, initialDialGate).also {
                        countingTransport = it
                    }
                }
            }
            protocols {
                add(Ping())
            }
        }
        hosts += client
        client.start().get(10, TimeUnit.SECONDS)
        return client to countingTransport
    }

    private class GatedCountingTcpTransport(
        upgrader: ConnectionUpgrader,
        initialDialGate: CompletableFuture<Unit>
    ) : TcpTransport(upgrader) {
        val dialCount = AtomicInteger()
        val dialGate = AtomicReference(initialDialGate)

        override fun dial(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Connection> {
            dialCount.incrementAndGet()
            val gateForThisDial = dialGate.get()
            return gateForThisDial.thenCompose {
                super.dial(addr, connHandler, preHandler)
            }
        }
    }

    companion object {
        private const val CALLER_COUNT = 16
        private const val PING_PROTOCOL = "/ipfs/ping/1.0.0"
    }
}
