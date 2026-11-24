package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Two physical connections between the same two peers while a large reply is being streamed,
 * the shape seen when a UrlResolver caller dials its relay twice at once. The reply is paused
 * half way at the responder, the duplicate decision is forced while it is paused, and only then
 * is the rest released. The whole reply must arrive and its parent must stay open.
 */
class NetworkDuplicateRetirementInFlightStreamTest {
    @Test
    fun `a reply in flight on the surplus dial survives duplicate settlement`() {
        withGatedHosts { client, _, transport ->
            val pending = client.network.connect(transport.serverPeer, *transport.serverAddresses)
            transport.start.complete(Unit)
            transport.secondUpgraded.get(10, TimeUnit.SECONDS)
            // The second dial settles first, so the first becomes the surplus parent.
            transport.secondResult.complete(Unit)
            val selected = pending.get(10, TimeUnit.SECONDS)
            val surplus = transport.firstUpgraded.get()
            assertThat(selected).isSameAs(transport.secondUpgraded.get())

            val reply = startBulk(client, surplus)
            transport.bulk.halfSent.get(10, TimeUnit.SECONDS)
            // Force the duplicate decision for the surplus parent while its reply is half sent.
            transport.firstResult.complete(Unit)
            transport.bulk.release.complete(Unit)

            assertThat(reply.get(20, TimeUnit.SECONDS)).isEqualTo(BULK_TOTAL_BYTES)
            assertThat(surplus.closeFuture().isDone).isFalse()
        }
    }

    @Test
    fun `a reply in flight on the selected parent survives retirement of the surplus dial`() {
        withGatedHosts { client, _, transport ->
            val pending = client.network.connect(transport.serverPeer, *transport.serverAddresses)
            transport.start.complete(Unit)
            transport.secondUpgraded.get(10, TimeUnit.SECONDS)
            transport.secondResult.complete(Unit)
            val selected = pending.get(10, TimeUnit.SECONDS)
            val surplus = transport.firstUpgraded.get()

            val reply = startBulk(client, selected)
            transport.bulk.halfSent.get(10, TimeUnit.SECONDS)
            transport.firstResult.complete(Unit)
            // The unused surplus parent is retired by both endpoints.
            surplus.closeFuture().get(10, TimeUnit.SECONDS)
            transport.bulk.release.complete(Unit)

            assertThat(reply.get(20, TimeUnit.SECONDS)).isEqualTo(BULK_TOTAL_BYTES)
            assertThat(selected.closeFuture().isDone).isFalse()
        }
    }

    @Test
    fun `a reply in flight on a stream the acceptor opened on the surplus dial survives duplicate settlement`() {
        withGatedHosts { client, server, transport ->
            val pending = client.network.connect(transport.serverPeer, *transport.serverAddresses)
            transport.start.complete(Unit)
            transport.secondUpgraded.get(10, TimeUnit.SECONDS)
            transport.secondResult.complete(Unit)
            pending.get(10, TimeUnit.SECONDS)
            val surplus = transport.firstUpgraded.get()
            val serverSide = server.network.connections.single {
                it.localAddress() == surplus.remoteAddress() && it.remoteAddress() == surplus.localAddress()
            }

            // The acceptor asks the initiator for a large reply on the surplus parent.
            val reply = startBulk(server, serverSide)
            transport.bulk.halfSent.get(10, TimeUnit.SECONDS)
            transport.firstResult.complete(Unit)
            transport.bulk.release.complete(Unit)

            assertThat(reply.get(20, TimeUnit.SECONDS)).isEqualTo(BULK_TOTAL_BYTES)
            assertThat(surplus.closeFuture().isDone).isFalse()
        }
    }
}

private const val BULK_PROTOCOL = "/test/bulk-reply/1.0.0"
private const val BULK_CHUNK_BYTES = 64 * 1024
private const val BULK_CHUNKS = 128
private const val BULK_TOTAL_BYTES = BULK_CHUNK_BYTES.toLong() * BULK_CHUNKS

/** Shared gate: the responder sends half the reply, reports it, and waits for release. */
private class BulkGate {
    val halfSent = CompletableFuture<Unit>()
    val release = CompletableFuture<Unit>()
}

private fun startBulk(host: Host, parent: Connection): CompletableFuture<Long> =
    host.newStream<CompletableFuture<Long>>(listOf(BULK_PROTOCOL), parent).controller
        .get(10, TimeUnit.SECONDS)

private class BulkBinding(gate: BulkGate) : StrictProtocolBinding<CompletableFuture<Long>>(BULK_PROTOCOL, BulkProtocol(gate))

private class BulkProtocol(private val gate: BulkGate) : ProtocolHandler<CompletableFuture<Long>>(Long.MAX_VALUE, Long.MAX_VALUE) {
    override fun onStartInitiator(stream: Stream): CompletableFuture<CompletableFuture<Long>> {
        val received = AtomicLong()
        val done = CompletableFuture<Long>()
        val started = CompletableFuture<CompletableFuture<Long>>()
        stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
            override fun onActivated(stream: Stream) {
                stream.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(1)))
                started.complete(done)
            }
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                if (received.addAndGet(msg.readableBytes().toLong()) == BULK_TOTAL_BYTES) done.complete(BULK_TOTAL_BYTES)
            }
            override fun onClosed(stream: Stream) {
                done.completeExceptionally(
                    IllegalStateException(
                        "Bulk reply stream closed after ${received.get()} of $BULK_TOTAL_BYTES bytes; parent closed=" +
                            stream.connection.closeFuture().isDone
                    )
                )
            }
            override fun onException(cause: Throwable?) {
                done.completeExceptionally(cause ?: IllegalStateException("Bulk reply failed without a cause"))
            }
        })
        return started
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<CompletableFuture<Long>> {
        stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                val half = BULK_CHUNKS / 2
                repeat(half) { stream.writeAndFlush(Unpooled.wrappedBuffer(ByteArray(BULK_CHUNK_BYTES))) }
                gate.halfSent.complete(Unit)
                gate.release.thenRun {
                    repeat(BULK_CHUNKS - half) { stream.writeAndFlush(Unpooled.wrappedBuffer(ByteArray(BULK_CHUNK_BYTES))) }
                }
            }
        })
        return CompletableFuture.completedFuture(CompletableFuture.completedFuture(0L))
    }
}

private fun withGatedHosts(body: (Host, Host, GatedTransport) -> Unit) {
    val gate = BulkGate()
    val server = host {
        network {
            listen("/ip4/127.0.0.1/tcp/0")
            listen("/ip4/127.0.0.2/tcp/0")
        }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        protocols { add(BulkBinding(gate)) }
    }
    lateinit var transport: GatedTransport
    val client = host {
        transports { add { upgrader -> GatedTransport(upgrader, gate).also { transport = it } } }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        protocols { add(BulkBinding(gate)) }
    }
    try {
        server.start().get(10, TimeUnit.SECONDS)
        client.start().get(10, TimeUnit.SECONDS)
        transport.serverPeer = server.peerId
        transport.serverAddresses = server.listenAddresses().toTypedArray()
        body(client, server, transport)
    } finally {
        transport.start.complete(Unit)
        transport.firstResult.complete(Unit)
        transport.secondResult.complete(Unit)
        gate.release.complete(Unit)
        try {
            client.stop().get(10, TimeUnit.SECONDS)
        } finally {
            server.stop().get(10, TimeUnit.SECONDS)
        }
    }
}

private class GatedTransport(upgrader: ConnectionUpgrader, val bulk: BulkGate) : TcpTransport(upgrader) {
    lateinit var serverPeer: io.libp2p.core.PeerId
    lateinit var serverAddresses: Array<Multiaddr>
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
