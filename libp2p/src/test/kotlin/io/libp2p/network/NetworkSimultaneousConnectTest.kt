package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkSimultaneousConnectTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `remote connection returned before its first stream survives duplicate settlement`(delayedPublication: Int) {
        checkRemoteReturnedParent(StreamMuxerProtocol.getYamux(), delayedPublication)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `mplex remote connection returned before its first stream survives duplicate settlement`(delayedPublication: Int) {
        checkRemoteReturnedParent(StreamMuxerProtocol.Mplex, delayedPublication)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `remote retention remains usable when the peer has no selection protocol`(delayedPublication: Int) {
        checkRemoteReturnedParent(StreamMuxerProtocol.getYamux(), delayedPublication, omitSelectionBinding = true)
    }

    @Test
    fun `stream opened directly on an inbound callback parent survives duplicate settlement`() {
        withSelectionHosts { client, server, transport ->
            val early = CompletableFuture<PingController>()
            val first = AtomicInteger()
            server.addConnectionHandler(
                ConnectionHandler.create { connection ->
                    if (first.incrementAndGet() == 1) {
                        server.newStream<PingController>(listOf(PING_PROTOCOL), connection).controller
                            .whenComplete { controller, error ->
                                if (error != null) early.completeExceptionally(error) else early.complete(controller)
                            }
                    }
                }
            )
            val pending = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
            transport.start.complete(Unit)
            transport.secondUpgraded.get(10, TimeUnit.SECONDS)
            val controller = early.get(10, TimeUnit.SECONDS)
            controller.ping().get(10, TimeUnit.SECONDS)
            transport.secondResult.complete(Unit)
            pending.get(10, TimeUnit.SECONDS)
            transport.firstResult.complete(Unit)
            controller.ping().get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `concurrent published reusers survive cancellation of one original caller`() {
        checkPublishedReusers(cancelAll = false)
    }

    @Test
    fun `concurrent published reusers survive cancellation of all original callers`() {
        checkPublishedReusers(cancelAll = true)
    }

    @Test
    fun `close reentry selects a live replacement before obsolete transport results finish`() {
        withSelectionHosts { client, server, transport ->
            val addresses = server.listenAddresses().toTypedArray()
            val pending = client.network.connect(server.peerId, *addresses)
            transport.start.complete(Unit)
            transport.secondUpgraded.get(10, TimeUnit.SECONDS)
            val selected = client.network.connect(server.peerId).get(10, TimeUnit.SECONDS)
            echoOn(client, selected)
            val reentrant = CompletableFuture<Connection>()
            selected.closeFuture().whenComplete { _, _ ->
                client.network.connect(server.peerId, ChannelVisitor {}, *addresses).whenComplete { connection, error ->
                    if (error != null) reentrant.completeExceptionally(error) else reentrant.complete(connection)
                }
            }
            val remote = server.network.connections.single {
                it.localAddress() == selected.remoteAddress() && it.remoteAddress() == selected.localAddress()
            }
            remote.close().get(10, TimeUnit.SECONDS)
            selected.closeFuture().get(10, TimeUnit.SECONDS)
            val replacement = reentrant.get(10, TimeUnit.SECONDS)
            assertThat(replacement).isNotSameAs(selected)
            echoOn(client, replacement)
            transport.secondResult.complete(Unit)
            transport.firstResult.complete(Unit)
            assertThat(pending.get(10, TimeUnit.SECONDS)).isSameAs(replacement)
            assertThat(client.network.connect(server.peerId).get(10, TimeUnit.SECONDS)).isSameAs(replacement)
            echoOn(client, replacement)
            assertThat(transport.count.get()).isEqualTo(2)
        }
    }
}

private fun checkRemoteReturnedParent(
    muxer: StreamMuxerProtocol,
    delayedPublication: Int,
    omitSelectionBinding: Boolean = false
) {
    val publication = CompletableFuture<Unit>()
    withSelectionHosts(muxer, omitSelectionBinding, delayedPublication, publication) { client, server, transport ->
        val published = CompletableFuture<Unit>()
        server.addConnectionHandler(ConnectionHandler.create { published.complete(Unit) })
        val pending = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
        transport.start.complete(Unit)
        transport.secondUpgraded.get(10, TimeUnit.SECONDS)
        published.get(10, TimeUnit.SECONDS)
        val remote = server.network.connect(client.peerId).get(10, TimeUnit.SECONDS)
        // Each endpoint retains a different physical parent, regardless of publication order.
        val remoteParent = if (delayedPublication == 1) transport.secondUpgraded.get() else transport.firstUpgraded.get()
        val localParent = if (delayedPublication == 1) transport.firstUpgraded.get() else transport.secondUpgraded.get()
        assertThat(remote.localAddress()).isEqualTo(remoteParent.remoteAddress())
        assertThat(remote.remoteAddress()).isEqualTo(remoteParent.localAddress())
        if (delayedPublication == 1) transport.firstResult.complete(Unit) else transport.secondResult.complete(Unit)
        val local = pending.get(10, TimeUnit.SECONDS)
        assertThat(local).isSameAs(localParent)
        // No application stream has used the remote returned parent yet.
        publication.complete(Unit)
        transport.firstResult.complete(Unit)
        transport.secondResult.complete(Unit)
        pingOn(server, remote)
        pingOn(client, local)
        assertThat(client.network.connect(server.peerId).get(10, TimeUnit.SECONDS)).isSameAs(local)
        assertThat(server.network.connect(client.peerId).get(10, TimeUnit.SECONDS)).isSameAs(remote)
        assertThat(transport.count.get()).isEqualTo(2)
    }
}

private fun checkPublishedReusers(cancelAll: Boolean) {
    withSelectionHosts { client, server, transport ->
        val addresses = server.listenAddresses().toTypedArray()
        val original = client.network.connect(server.peerId, *addresses)
        val subscriber = client.network.connect(server.peerId, *addresses)
        transport.start.complete(Unit)
        transport.secondUpgraded.get(10, TimeUnit.SECONDS)
        val executor = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        try {
            val callers = (0 until 16).map { index ->
                CompletableFuture.supplyAsync({
                    check(start.await(10, TimeUnit.SECONDS))
                    if (index % 2 == 0) {
                        client.network.connect(server.peerId).get(10, TimeUnit.SECONDS)
                    } else {
                        val opening = client.newStream<PingController>(listOf(PING_PROTOCOL), server.peerId, *addresses)
                        opening.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS)
                        opening.stream.get(10, TimeUnit.SECONDS).connection
                    }
                }, executor)
            }
            start.countDown()
            val selected = callers.map { it.get(10, TimeUnit.SECONDS) }
            assertThat(selected).allMatch { it === selected.first() }
            assertThat(original.cancel(true)).isTrue()
            if (cancelAll) assertThat(subscriber.cancel(true)).isTrue() else assertThat(subscriber.isDone).isFalse()
            val live = selected.first()
            val ping = client.newStream<PingController>(listOf(PING_PROTOCOL), live).controller.get(10, TimeUnit.SECONDS)
            ping.ping().get(10, TimeUnit.SECONDS)
            transport.secondResult.complete(Unit)
            transport.firstResult.complete(Unit)
            if (!cancelAll) assertThat(subscriber.get(10, TimeUnit.SECONDS)).isSameAs(live)
            val loser = listOf(transport.firstUpgraded.get(), transport.secondUpgraded.get()).single { it !== live }
            loser.closeFuture().get(10, TimeUnit.SECONDS)
            assertThat(live.closeFuture().isDone).isFalse()
            ping.ping().get(10, TimeUnit.SECONDS)
            assertThat(transport.count.get()).isEqualTo(2)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}

private const val PING_PROTOCOL = "/ipfs/ping/1.0.0"

private fun echoOn(host: Host, parent: Connection) {
    val controller = host.newStream<EchoController>(listOf("/test/echo"), parent).controller.get(10, TimeUnit.SECONDS)
    assertThat(controller.echo("retained parent").get(10, TimeUnit.SECONDS)).isEqualTo("retained parent")
}

private fun pingOn(host: Host, parent: Connection) {
    host.newStream<PingController>(listOf(PING_PROTOCOL), parent).controller
        .get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS)
}

private fun withSelectionHosts(
    muxer: StreamMuxerProtocol = StreamMuxerProtocol.getYamux(),
    omitSelectionBinding: Boolean = false,
    delayedPublication: Int? = null,
    publication: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit),
    body: (Host, Host, SelectionTestTransport) -> Unit
) {
    val server = host {
        if (delayedPublication != null) muxerMultistreamProtocol = SelectionPublicationOrder(delayedPublication, publication)
        network {
            listen("/ip4/127.0.0.1/tcp/0")
            listen("/ip4/127.0.0.2/tcp/0")
        }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(selectionAwareMuxer(muxer, omitSelectionBinding)) }
        protocols {
            add(Ping())
            add(Echo())
        }
    }
    lateinit var transport: SelectionTestTransport
    val client = host {
        transports { add { upgrader -> SelectionTestTransport(upgrader).also { transport = it } } }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(selectionAwareMuxer(muxer, omitSelectionBinding)) }
        protocols {
            add(Ping())
            add(Echo())
        }
    }
    try {
        server.start().get(10, TimeUnit.SECONDS)
        client.start().get(10, TimeUnit.SECONDS)
        body(client, server, transport)
    } finally {
        publication.complete(Unit)
        transport.start.complete(Unit)
        transport.firstResult.complete(Unit)
        transport.secondResult.complete(Unit)
        try {
            client.stop().get(10, TimeUnit.SECONDS)
        } finally {
            server.stop().get(10, TimeUnit.SECONDS)
        }
        (client.network.transports + server.network.transports).forEach {
            assertThat(it.activeConnections).isZero()
            assertThat(it.activeListeners).isZero()
        }
    }
}

private fun selectionAwareMuxer(
    muxer: StreamMuxerProtocol,
    omitSelectionBinding: Boolean
): StreamMuxerProtocol = StreamMuxerProtocol { multistream, protocols ->
    muxer.createMuxer(
        multistream,
        if (omitSelectionBinding) {
            protocols.filterNot { it.protocolDescriptor.matchesAny(listOf("/libp2p/connection-selection/1.0.0")) }
        } else {
            protocols
        }
    )
}

private class SelectionTestTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val count = AtomicInteger()
    val start = CompletableFuture<Unit>()
    val firstUpgraded = CompletableFuture<Connection>()
    val secondUpgraded = CompletableFuture<Connection>()
    val firstResult = CompletableFuture<Unit>()
    val secondResult = CompletableFuture<Unit>()
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> =
        when (count.incrementAndGet()) {
            1 -> start.thenCompose { super.dial(addr, connHandler, preHandler) }.thenCompose { connection ->
                firstUpgraded.complete(connection)
                firstResult.thenApply { connection }
            }
            2 -> firstUpgraded.thenCompose { super.dial(addr, connHandler, preHandler) }.thenCompose { connection ->
                secondUpgraded.complete(connection)
                secondResult.thenApply { connection }
            }
            else -> error("The fixture expected exactly two transport dials")
        }
}

private class SelectionPublicationOrder(
    private val delayed: Int,
    private val publication: CompletableFuture<Unit>
) : MultistreamProtocol {
    private val count = AtomicInteger()
    override val version = MultistreamProtocolV1.version
    override fun <T> createMultistream(bindings: List<ProtocolBinding<T>>): Multistream<T> {
        val real = MultistreamProtocolV1.createMultistream(bindings)
        return object : Multistream<T> {
            override val bindings = real.bindings
            override fun initChannel(ch: P2PChannel): CompletableFuture<T> =
                real.initChannel(ch).thenCompose { controller ->
                    if (count.incrementAndGet() == delayed) {
                        publication.thenApply { controller }
                    } else {
                        CompletableFuture.completedFuture(controller)
                    }
                }
        }
    }
}
