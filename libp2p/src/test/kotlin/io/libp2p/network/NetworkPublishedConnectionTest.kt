package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NetworkPublishedConnectionTest {
    @Test
    fun `a parent selected from its callback survives a competing dial result`() {
        val server = publishedConnectionHost(listen = true)
        lateinit var transport: PublishedConnectionTcpTransport
        val client = publishedConnectionHost { upgrader ->
            PublishedConnectionTcpTransport(upgrader).also { transport = it }
        }
        val selectedFromCallback = CompletableFuture<Connection>()
        val firstCallback = AtomicBoolean(true)

        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val addresses = server.listenAddresses().toTypedArray()
            client.addConnectionHandler(ConnectionHandler.create {
                if (firstCallback.compareAndSet(true, false)) {
                    client.network.connect(server.peerId, *addresses).whenComplete { connection, error ->
                        if (error == null) selectedFromCallback.complete(connection)
                        else selectedFromCallback.completeExceptionally(error)
                    }
                }
            })

            val settled = client.network.connect(server.peerId, *addresses).get(10, TimeUnit.SECONDS)
            transport.releaseFirst.complete(Unit)
            val selected = selectedFromCallback.get(10, TimeUnit.SECONDS)
            val earlier = transport.firstUpgraded.get(10, TimeUnit.SECONDS)
            val later = transport.secondUpgraded.get(10, TimeUnit.SECONDS)
            listOf(earlier, later).filterNot { it === settled }.forEach {
                it.closeFuture().get(10, TimeUnit.SECONDS)
            }

            assertThat(selected.closeFuture().isDone)
                .describedAs("A connection selected by a reentrant caller must stay open after both dials settle")
                .isFalse()
            assertThat(selected).isSameAs(settled)
            client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), selected)
                .controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS)
        } finally {
            transport.releaseFirst.complete(Unit)
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `a selected parent survives completion of an earlier published dial`() {
        val server = publishedConnectionHost(listen = true)
        lateinit var transport: PublishedConnectionTcpTransport
        val client = publishedConnectionHost { upgrader ->
            PublishedConnectionTcpTransport(upgrader).also { transport = it }
        }

        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val addresses = server.listenAddresses().toTypedArray()
            assertThat(addresses).hasSize(2)

            val settled = client.network.connect(server.peerId, *addresses).get(10, TimeUnit.SECONDS)
            val earlier = transport.firstUpgraded.get(10, TimeUnit.SECONDS)
            assertThat(transport.dialCount.get()).isEqualTo(2)

            val selected = client.network.connect(server.peerId, *addresses).get(10, TimeUnit.SECONDS)
            val ping = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), selected)
                .controller.get(10, TimeUnit.SECONDS)
            ping.ping().get(10, TimeUnit.SECONDS)

            transport.releaseFirst.complete(Unit)
            val later = transport.secondUpgraded.get(10, TimeUnit.SECONDS)
            listOf(earlier, later).filterNot { it === settled }.forEach {
                it.closeFuture().get(10, TimeUnit.SECONDS)
            }

            assertThat(selected.closeFuture().isDone)
                .describedAs("The caller's selected connection must stay open after the delayed dial settles")
                .isFalse()
            assertThat(selected).isSameAs(settled)
            ping.ping().get(10, TimeUnit.SECONDS)
        } finally {
            transport.releaseFirst.complete(Unit)
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}

private fun publishedConnectionHost(
    listen: Boolean = false,
    transportFactory: ((ConnectionUpgrader) -> TcpTransport)? = null
): Host = host {
    if (listen) {
        network {
            listen("/ip4/127.0.0.1/tcp/0")
            listen("/ip4/127.0.0.2/tcp/0")
        }
    }
    transportFactory?.let { factory -> transports { add(factory) } }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    protocols { add(Ping()) }
}

/** Holds only the transport result, after real TCP, Noise, mux and connection callbacks finish. */
private class PublishedConnectionTcpTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val dialCount = AtomicInteger()
    val firstUpgraded = CompletableFuture<Connection>()
    val secondUpgraded = CompletableFuture<Connection>()
    val releaseFirst = CompletableFuture<Unit>()
    private val startFirst = CompletableFuture<Unit>()

    override fun dial(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Connection> = when (dialCount.incrementAndGet()) {
        1 -> startFirst.thenCompose {
            super.dial(addr, connHandler, preHandler)
        }.thenCompose { connection ->
            firstUpgraded.complete(connection)
            releaseFirst.thenApply { connection }
        }
        2 -> {
            startFirst.complete(Unit)
            firstUpgraded.thenCompose { super.dial(addr, connHandler, preHandler) }.thenApply {
                secondUpgraded.complete(it)
                it
            }
        }
        else -> error("The fixture expected exactly two transport dials")
    }
}
