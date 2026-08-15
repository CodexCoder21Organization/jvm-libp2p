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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkSimultaneousConnectTest {
    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun stopHosts() {
        hosts.asReversed().forEach { it.stop().get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun `simultaneous peer connects leave one shared connection open`() {
        val releaseBothDials = CompletableFuture<Unit>()
        val (first, firstTransport) = createHost(releaseBothDials)
        val (second, secondTransport) = createHost(releaseBothDials)

        val firstConnect = first.network.connect(
            second.peerId,
            second.listenAddresses().single()
        )
        val secondConnect = second.network.connect(
            first.peerId,
            first.listenAddresses().single()
        )

        assertThat(firstTransport.dialCount.get()).isEqualTo(1)
        assertThat(secondTransport.dialCount.get()).isEqualTo(1)
        assertThat(firstConnect.isDone).isFalse()
        assertThat(secondConnect.isDone).isFalse()

        releaseBothDials.complete(Unit)
        CompletableFuture.allOf(firstConnect, secondConnect).get(30, TimeUnit.SECONDS)

        assertThat(activeConnectionCount(first))
            .describedAs("active connections the first peer holds to the second")
            .isEqualTo(1)
        assertThat(activeConnectionCount(second))
            .describedAs("active connections the second peer holds to the first")
            .isEqualTo(1)
    }

    private fun createHost(dialGate: CompletableFuture<Unit>): Pair<Host, GatedTcpTransport> {
        lateinit var transport: GatedTcpTransport
        val created = host {
            network {
                listen("/ip4/127.0.0.1/tcp/0")
            }
            transports {
                add { upgrader ->
                    GatedTcpTransport(upgrader, dialGate).also { transport = it }
                }
            }
            protocols {
                add(Ping())
            }
        }
        hosts += created
        created.start().get(30, TimeUnit.SECONDS)
        return created to transport
    }

    private fun activeConnectionCount(host: Host): Int =
        host.network.connections.count { !it.closeFuture().isDone }

    private class GatedTcpTransport(
        upgrader: ConnectionUpgrader,
        private val dialGate: CompletableFuture<Unit>
    ) : TcpTransport(upgrader) {
        val dialCount = AtomicInteger()

        override fun dial(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Connection> {
            dialCount.incrementAndGet()
            return dialGate.thenCompose { super.dial(addr, connHandler, preHandler) }
        }
    }
}
