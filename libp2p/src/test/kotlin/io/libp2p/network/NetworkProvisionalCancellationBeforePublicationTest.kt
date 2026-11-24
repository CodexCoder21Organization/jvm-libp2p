package io.libp2p.network

import io.libp2p.core.*
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.security.SecureChannel
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NetworkProvisionalCancellationBeforePublicationTest {
    @Test
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
        ) {}
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
            // Initiator readiness does not order the peer responder callback.
            responderStarted.get(5, SECONDS)
            assertSame(stable, later.stream.get(5, SECONDS).connection, "The retained parent must still serve a later child")
            later.stream.get(5, SECONDS).close().get(5, SECONDS)
            assertEquals(1, responderStarts.get(), "Only the later child on the retained parent may reach its responder")
            assertFalse(stable.closeFuture().isDone, "Retiring the surplus parent must leave the stable parent open")
        } finally {
            gates.forEach { it.complete(Unit) }
            releasePublication.countDown()
            try {
                client.stop().get(10, SECONDS)
            } finally {
                server.stop().get(10, SECONDS)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun cancelledDuringChildInitializationReportsBothResults(mplex: Boolean) {
        val creationEntered = CountDownLatch(1)
        val releaseCreation = CountDownLatch(1)
        val cancellationEntered = CountDownLatch(1)
        val releaseCancellation = CountDownLatch(1)
        val canceller = Executors.newSingleThreadExecutor()
        val source = AtomicReference<StreamPromise<*>>()
        val localVisits = AtomicInteger()
        val muxer = if (mplex) StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        val gatedMuxer = StreamMuxerProtocol { multistream, protocols ->
            val delegate = muxer.createMuxer(multistream, protocols)
            object : StreamMuxer by delegate {
                override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> =
                    delegate.initChannel(ch, selectedProtocol).thenApply { session ->
                        object : StreamMuxer.Session by session {
                            override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
                                val promise = session.createStream(protocols)
                                if (protocols.any { it.protocolDescriptor.matchesAny(listOf("/test/provisional-parent/1.0.0")) }) {
                                    source.set(promise)
                                    // Store cancellation first, then hold its cleanup while real child creation resumes.
                                    promise.controller.whenComplete { _, _ ->
                                        if (promise.controller.isCancelled) {
                                            cancellationEntered.countDown()
                                            check(releaseCancellation.await(10, SECONDS)) {
                                                "The test must release the controller cancellation continuation"
                                            }
                                        }
                                    }
                                }
                                return promise
                            }
                        }
                    }
            }
        }
        val gates = List(2) { CompletableFuture<Unit>() }
        val starts = List(2) { CountDownLatch(1) }
        val handshakes = AtomicInteger()
        val provisional = AtomicReference<Connection>()
        val published = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val provisionalClosed = CountDownLatch(1)
        val remoteProvisionalClosed = CompletableFuture<Unit>()
        val initiatorStarts = AtomicInteger()
        val responderStarts = AtomicInteger()
        val responderStarted = CompletableFuture<Unit>()
        val protocol = object : StrictProtocolBinding<Unit>(
            "/test/provisional-parent/1.0.0",
            object : ProtocolHandler<Unit>(1, 1) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Unit> {
                    initiatorStarts.incrementAndGet()
                    return CompletableFuture.completedFuture(Unit)
                }
                override fun onStartResponder(stream: Stream): CompletableFuture<Unit> {
                    responderStarts.incrementAndGet()
                    responderStarted.complete(Unit)
                    return CompletableFuture.completedFuture(Unit)
                }
            },
        ) {}
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
            muxers { +muxer }
            network {
                listen("/ip4/127.0.0.1/tcp/0")
                listen("/ip4/127.0.0.2/tcp/0")
            }
            protocols {
                +protocol
                +Echo()
            }
        }
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +gatedMuxer }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols {
                +protocol
                +Echo()
            }
        }
        client.addStreamVisitor(ChannelVisitor { localVisits.incrementAndGet() })
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
            server.start().awaitStreamCreationStep("Server startup", 10)
            client.start().awaitStreamCreationStep("Client startup", 10)
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
            val stable = stableDial.awaitStreamCreationStep("Stable parent dial", 10)
            gates[1].complete(Unit)
            assertTrue(published.await(5, SECONDS), "The second parent must reach its held publication callback")
            assertEquals(2, client.network.connections.size, "Both physical parents must be visible before provisional child admission")
            val unused = provisional.get()
            val siblingHold = checkNotNull(client.network.holdConnection(unused).awaitStreamCreationStep("Provisional parent hold"))
            val accepted = client.newStream<EchoController>(listOf("/test/echo"), siblingHold)
            releasePublication.countDown()
            assertSame(stable, provisionalDial.awaitStreamCreationStep("Surplus parent dial selection"), "The surplus dial must select the retained parent")
            val echo = accepted.controller.awaitStreamCreationStep("Accepted sibling controller")
            val acceptedStream = accepted.stream.awaitStreamCreationStep("Accepted sibling stream")
            assertSame(unused, acceptedStream.connection, "The accepted sibling must use the surplus physical parent")
            assertEquals("before cancellation", echo.echo("before cancellation").awaitStreamCreationStep("Sibling echo before cancellation"))
            val visitsBeforeCancellation = localVisits.get()
            val loop = (unused as ConnectionOverNetty).nettyChannel.eventLoop()
            val blockedCreation = loop.submit {
                creationEntered.countDown()
                check(releaseCreation.await(10, SECONDS)) { "The test must release queued child creation" }
            }
            assertTrue(creationEntered.await(5, SECONDS), "The parent event loop must be held before child creation is queued")
            val hold = checkNotNull(client.network.holdConnection(unused).awaitStreamCreationStep("Provisional parent hold"))
            val child = client.newStream<Unit>(listOf("/test/provisional-parent/1.0.0"), hold)
            val cancelled = canceller.submit<Boolean> { child.controller.cancel(false) }
            assertTrue(cancellationEntered.await(5, SECONDS), "The source controller must be cancelled before child initialization resumes")
            assertTrue(source.get().controller.isCancelled, "The real mux controller must already store cancellation")
            releaseCreation.countDown()
            blockedCreation.awaitStreamCreationStep("Released child creation gate")
            // This marker runs after queued creation, including closing the late-created child.
            loop.submit {}.awaitStreamCreationStep("Child initialization and close marker")
            releaseCancellation.countDown()
            assertTrue(cancelled.awaitStreamCreationStep("Controller cancellation caller"), "The caller must retain its original cancellation result")
            val expectedMessage = "Stream creation was cancelled before initialization on connection ${unused.nettyChannel}"
            assertAll(
                {
                    source.get().stream.handle { stream, failure ->
                        assertNotNull(failure, "The mux stream completed normally with $stream instead of reporting cancellation")
                        val cause = if (failure is CompletionException) failure.cause else failure
                        assertInstanceOf(CancellationException::class.java, cause)
                        assertEquals(expectedMessage, cause?.message)
                    }.awaitStreamCreationStep("Mux source stream result")
                },
                {
                    val failure = assertDoesNotThrow<Throwable>(
                        { child.stream.handle { _, cause -> cause }.awaitStreamCreationStep("Provisional child stream result") },
                        "The cancelled provisional stream must report its result within the existing five-second deadline"
                    )
                    val cause = if (failure is CompletionException) failure.cause else failure
                    assertInstanceOf(CancellationException::class.java, cause)
                    assertEquals(expectedMessage, cause?.message)
                }
            )
            assertTrue(child.controller.isCancelled, "Child initialization must not replace caller cancellation")
            assertFalse(hold.promote(), "Cancellation must release its caller's provisional hold")
            hold.close()
            hold.close()
            assertEquals(visitsBeforeCancellation, localVisits.get(), "No local visitor may receive the cancelled late-created child")
            assertEquals(0, initiatorStarts.get(), "The cancelled child must not run its initiator handler")
            assertEquals(0, responderStarts.get(), "The cancelled child must never offer its protocol to the responder")
            assertEquals("after cancellation", echo.echo("after cancellation").awaitStreamCreationStep("Sibling echo after cancellation"))
            assertFalse(unused.closeFuture().isDone, "Releasing the cancelled child twice must leave its independent sibling protected")
            val laterHold = checkNotNull(client.network.holdConnection(unused).awaitStreamCreationStep("Provisional parent hold"))
            val later = client.newStream<Unit>(listOf("/test/provisional-parent/1.0.0"), laterHold)
            later.controller.awaitStreamCreationStep("Later child controller")
            responderStarted.awaitStreamCreationStep("Later child responder")
            assertSame(unused, later.stream.awaitStreamCreationStep("Later child stream").connection, "A later child must work on the exact same physical parent")
            assertEquals(1, initiatorStarts.get(), "Only the later child may run its initiator")
            assertEquals(1, responderStarts.get(), "Only the later child may run its responder")
            assertEquals(2, client.streams.size, "Only the accepted sibling and later child may remain on the client")
            assertEquals(2, server.streams.size, "The peer must have closed the cancelled child before accepting the later protocol")
            laterHold.close()
            siblingHold.close()
            later.stream.awaitStreamCreationStep("Later child stream").close().awaitStreamCreationStep("Accepted child close")
            assertEquals("after later child closes", echo.echo("after later child closes").awaitStreamCreationStep("Sibling echo after later child closes"))
            acceptedStream.close().awaitStreamCreationStep("Accepted child close")
            assertTrue(provisionalClosed.await(5, SECONDS), "The cancelled child's use must be released so the surplus parent retires after its last sibling closes")
            remoteProvisionalClosed.awaitStreamCreationStep("Remote surplus parent close")
            assertFalse(stable.closeFuture().isDone, "The retained physical parent must remain usable")
        } finally {
            gates.forEach { it.complete(Unit) }
            releasePublication.countDown()
            releaseCreation.countDown()
            releaseCancellation.countDown()
            canceller.shutdownNow()
            try {
                assertTrue(canceller.awaitTermination(5, SECONDS), "The controller cancellation helper must stop")
            } finally {
                try {
                    client.stop().awaitStreamCreationStep("Client shutdown", 10)
                } finally {
                    server.stop().awaitStreamCreationStep("Server shutdown", 10)
                }
            }
        }
    }
}

private fun <T> Future<T>.awaitStreamCreationStep(step: String, seconds: Long = 5): T =
    try {
        get(seconds, SECONDS)
    } catch (failure: TimeoutException) {
        throw AssertionError("$step did not finish within $seconds seconds", failure)
    }
