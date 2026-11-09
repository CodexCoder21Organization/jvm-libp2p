package io.libp2p.network

import io.libp2p.core.*
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.security.SecureChannel
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NetworkProvisionalYamuxCancellationOrderingEvidenceTest {
    @RepeatedTest(10)
    @Execution(CONCURRENT)
    fun testCancelledProvisionalProbeStopsBeforeHeldParentPublishes() {
        val gates = List(2) { CompletableFuture<Unit>() }
        val starts = List(2) { CountDownLatch(1) }
        val handshakes = AtomicInteger()
        val provisional = AtomicReference<Connection>()
        val published = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val provisionalClosed = CountDownLatch(1)
        val remoteProvisionalClosed = CompletableFuture<Unit>()
        val responderStarts = AtomicInteger()
        val responderEntered = CompletableFuture<Unit>()
        val releaseResponder = CountDownLatch(1)
        val responderStarted = CompletableFuture<Unit>()
        val protocol = object : StrictProtocolBinding<Unit>(
            "/test/provisional-parent/1.0.0",
            object : ProtocolHandler<Unit>(1, 1) {
                override fun onStartInitiator(stream: Stream) = CompletableFuture.completedFuture(Unit)
                override fun onStartResponder(stream: Stream): CompletableFuture<Unit> {
                    responderStarts.incrementAndGet()
                    responderStarted.complete(Unit)
                    return CompletableFuture.completedFuture(Unit)
                }
            },
        ) {
            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<Unit> {
                if (!(ch as Stream).isInitiator) {
                    responderEntered.complete(Unit)
                    check(releaseResponder.await(5, SECONDS)) { "The scenario must release the later responder binding" }
                }
                return super.initChannel(ch, selectedProtocol)
            }
        }
        val server = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels {
                add { key, _ ->
                    val delegate = NoiseXXSecureChannel(key)
                    object : SecureChannel by delegate {
                        override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
                            val ordinal = handshakes.getAndIncrement()
                            check(ordinal < 2) { "Only the two requested physical handshakes may start" }
                            ch.pushHandler(
                                "hold-provisional-handshake",
                                object : io.netty.channel.ChannelOutboundHandlerAdapter() {
                                    private val held = mutableListOf<Pair<Any, io.netty.channel.ChannelPromise>>()
                                    private var released = false
                                    override fun handlerAdded(ctx: io.netty.channel.ChannelHandlerContext) {
                                        gates[ordinal].thenRun {
                                            ctx.executor().execute {
                                                released = true
                                                val writes = held.toList()
                                                held.clear()
                                                writes.forEach { (message, promise) -> ctx.write(message, promise) }
                                                ctx.flush()
                                            }
                                        }
                                    }
                                    override fun write(ctx: io.netty.channel.ChannelHandlerContext, message: Any, promise: io.netty.channel.ChannelPromise) {
                                        if (released) ctx.write(message, promise) else held.add(message to promise)
                                    }
                                }
                            )
                            starts[ordinal].countDown()
                            return delegate.initChannel(ch, selectedProtocol)
                        }
                    }
                }
            }
            muxers { +StreamMuxerProtocol.getYamux() }
            network {
                listen("/ip4/127.0.0.1/tcp/0")
                listen("/ip4/127.0.0.2/tcp/0")
            }
            protocols { +protocol }
        }
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +StreamMuxerProtocol.getYamux() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { +protocol }
        }
        server.addConnectionHandler(
            ConnectionHandler.create { connection ->
                if (connection.localAddress().toString().contains("/ip4/127.0.0.2/")) {
                    connection.closeFuture().thenRun { remoteProvisionalClosed.complete(Unit) }
                }
            }
        )
        client.addConnectionHandler(
            ConnectionHandler.create { connection ->
                if (connection === provisional.get()) {
                    connection.closeFuture().thenRun { provisionalClosed.countDown() }
                    published.countDown()
                    check(releasePublication.await(10, SECONDS)) { "The scenario must release provisional publication" }
                }
            }
        )
        try {
            server.start().get(10, SECONDS)
            client.start().get(10, SECONDS)
            val addresses = server.listenAddresses()
            assertEquals(2, addresses.size, "Both physical dials need their own real address")
            val stableDial = client.network.connect(server.peerId, addresses[0])
            assertTrue(starts[0].await(5, SECONDS), "The stable parent's handshake must begin first")
            val provisionalDial = client.network.connect(
                server.peerId,
                ChannelVisitor<P2PChannel> {
                    provisional.set(it as Connection)
                },
                addresses[1]
            )
            assertTrue(starts[1].await(5, SECONDS), "The provisional dial must start before either parent publishes")
            assertEquals(0, client.network.connections.size, "Both real handshakes must still be held")
            gates[0].complete(Unit)
            val stable = stableDial.get(10, SECONDS)
            gates[1].complete(Unit)
            assertTrue(published.await(5, SECONDS), "The second parent must reach its held publication callback")
            assertEquals(2, client.network.connections.size, "Both physical parents must be visible before provisional child admission")
            val unused = provisional.get()
            val hold = checkNotNull(client.network.holdConnection(unused).get(5, SECONDS))
            val child = client.newStream<Unit>(listOf("/test/provisional-parent/1.0.0"), hold)
            child.stream.thenAccept { it.close() }
            assertTrue(child.controller.cancel(false), "The provisional child must be abandoned before negotiation completes")
            releasePublication.countDown()
            assertSame(stable, provisionalDial.get(5, SECONDS), "The surplus physical dial must select the already settled parent")
            assertTrue(
                provisionalClosed.await(5, SECONDS),
                "An abandoned provisional child must not keep the unused physical parent open after its dial selects the stable parent"
            )
            remoteProvisionalClosed.get(5, SECONDS)
            assertEquals(
                0,
                responderStarts.get(),
                "A provisional child cancelled before its parent publishes must not start its responder after publication resumes"
            )
            val later = client.newStream<Unit>(listOf("/test/provisional-parent/1.0.0"), stable)
            later.controller.get(5, SECONDS)
            responderEntered.get(5, SECONDS)
            assertSame(stable, later.stream.get(5, SECONDS).connection, "The retained parent must still serve a later child")
            later.stream.get(5, SECONDS).close().get(5, SECONDS)
            assertEquals(1, responderStarts.get(), "Only the later child on the retained parent may reach its responder")
            assertFalse(stable.closeFuture().isDone, "Retiring the surplus parent must leave the stable parent open")
        } finally {
            releaseResponder.countDown()
            gates.forEach { it.complete(Unit) }
            releasePublication.countDown()
            try {
                client.stop().get(10, SECONDS)
            } finally {
                server.stop().get(10, SECONDS)
            }
        }
    }
}
