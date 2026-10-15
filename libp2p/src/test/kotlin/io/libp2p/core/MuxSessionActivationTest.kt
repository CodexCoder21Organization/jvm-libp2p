package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerDebug
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MuxSessionActivationTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
        "yamux,cancel", "mplex,cancel",
        "yamux,fail", "mplex,fail",
        "yamux,complete", "mplex,complete"
    )
    fun callerCompletedStreamFutureDoesNotHideParentCloseFromController(muxerName: String, mutation: String) {
        val published = CompletableFuture<StreamPromise<PingController>>()
        withPublicationHosts(publicationMuxer(muxerName), { client ->
            client.addConnectionHandler(ConnectionHandler.create { connection ->
                val pending = connection.muxerSession().createStream(Ping())
                when (mutation) {
                    "cancel" -> pending.stream.cancel(false)
                    "fail" -> pending.stream.completeExceptionally(IllegalStateException("Caller completed its stream future"))
                    "complete" -> pending.stream.complete(null)
                    else -> error("Unknown future mutation: $mutation")
                }
                connection.close().whenComplete { _, error ->
                    if (error == null) published.complete(pending) else published.completeExceptionally(error)
                }
            })
        }) { client, server ->
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS)
            val promise = published.get(5, TimeUnit.SECONDS)
            when (mutation) {
                "cancel" -> assertThrows(java.util.concurrent.CancellationException::class.java) {
                    promise.stream.get(5, TimeUnit.SECONDS)
                }
                "fail" -> {
                    val failure = assertThrows(ExecutionException::class.java) { promise.stream.get(5, TimeUnit.SECONDS) }
                    assertEquals(IllegalStateException::class.java, failure.cause!!.javaClass)
                    assertEquals("Caller completed its stream future", failure.cause!!.message)
                }
                "complete" -> org.junit.jupiter.api.Assertions.assertNull(promise.stream.get(5, TimeUnit.SECONDS))
            }
            assertTrue(promise.controller.isDone, "Changing the stream future must not leave its controller pending after close")
            val failure = assertThrows(ExecutionException::class.java) { promise.controller.get(5, TimeUnit.SECONDS) }
            assertEquals(ConnectionClosedException::class.java, failure.cause!!.javaClass)
        }
    }


    @ParameterizedTest
    @ValueSource(strings = ["yamux", "mplex"])
    fun closeDuringHandlerInstallationFailsSessionReadiness(muxerName: String) {
        val readiness = CompletableFuture<CompletableFuture<out StreamMuxer.Session>>()
        val muxer = StreamMuxerProtocol { multistream, protocols ->
            val delegate = publicationMuxer(muxerName).createMuxer(multistream, protocols)
            (delegate as StreamMuxerDebug).muxFramesDebugHandler = ChannelVisitor { connection ->
                connection.close()
            }
            object : StreamMuxer by delegate {
                override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> {
                    return delegate.initChannel(ch, selectedProtocol).also { readiness.complete(it) }
                }
            }
        }
        withPublicationHosts(muxer) { client, server ->
            val connected = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
            val session = readiness.get(5, TimeUnit.SECONDS)
            val failure = assertThrows(ExecutionException::class.java) { session.get(5, TimeUnit.SECONDS) }
            assertEquals(ConnectionClosedException::class.java, failure.cause!!.javaClass)
            assertThrows(ExecutionException::class.java) { connected.get(5, TimeUnit.SECONDS) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["yamux", "mplex"])
    fun sessionIsPublishedAfterActivation(muxerName: String) {
        val activated = AtomicBoolean()
        val observed = CompletableFuture<Boolean>()
        val muxer = observingPublicationMuxer(muxerName) { _, context ->
            activated.set(true)
            context.fireChannelActive()
        }
        withPublicationHosts(muxer, { client ->
            client.addConnectionHandler(
                ConnectionHandler.create {
                    observed.complete(activated.get())
                }
            )
        }) { client, server ->
            val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS)
            assertTrue(observed.get(5, TimeUnit.SECONDS), "The mux lifecycle must activate before publishing its connection")
            val ping = connection.muxerSession().createStream(Ping()).controller.get(5, TimeUnit.SECONDS)
            ping.ping().get(5, TimeUnit.SECONDS)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["yamux", "mplex"])
    fun closeBeforeActivationFailsConnectionWithoutPublishingIt(muxerName: String) {
        val publications = AtomicInteger()
        val closed = CompletableFuture<Void>()
        val muxer = observingPublicationMuxer(muxerName) { connection, context ->
            connection.close().whenComplete { _, error ->
                if (error == null) closed.complete(null) else closed.completeExceptionally(error)
            }
            context.fireChannelActive()
        }
        withPublicationHosts(muxer, { client ->
            client.addConnectionHandler(ConnectionHandler.create { publications.incrementAndGet() })
        }) { client, server ->
            val connected = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
            closed.get(5, TimeUnit.SECONDS)
            assertThrows(ExecutionException::class.java) { connected.get(5, TimeUnit.SECONDS) }
            assertEquals(0, publications.get(), "A parent closed before activation must never be published")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["yamux", "mplex"])
    fun queuedOpensAndCompletionReentrySettleWhenPublicationClosesParent(muxerName: String) {
        val pending = CompletableFuture<List<StreamPromise<PingController>>>()
        val reentered = CompletableFuture<StreamPromise<PingController>>()
        val completionCount = AtomicInteger()
        val settledAtClose = CompletableFuture<Boolean>()
        withPublicationHosts(publicationMuxer(muxerName), { client ->
            client.addConnectionHandler(
                ConnectionHandler.create { connection ->
                    val opens = List(32) { connection.muxerSession().createStream(Ping()) }
                    opens.first().controller.whenComplete { _, _ ->
                        completionCount.incrementAndGet()
                        reentered.complete(connection.muxerSession().createStream(Ping()))
                    }
                    connection.close().whenComplete { _, error ->
                        if (error != null) {
                            settledAtClose.completeExceptionally(error)
                        } else {
                            settledAtClose.complete(opens.all { it.stream.isCompletedExceptionally && it.controller.isCompletedExceptionally })
                        }
                    }
                    connection.close()
                    pending.complete(opens)
                }
            )
        }) { client, server ->
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS)
            assertTrue(settledAtClose.get(5, TimeUnit.SECONDS), "Pending stream opens must fail before close completes")
            pending.get(5, TimeUnit.SECONDS).forEach(::assertPublicationClosed)
            assertPublicationClosed(reentered.get(5, TimeUnit.SECONDS))
            assertEquals(1, completionCount.get(), "Closing twice must complete each caller once")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["yamux", "mplex"])
    fun closeAfterSuccessfulPingRejectsNewStreams(muxerName: String) {
        withPublicationHosts(publicationMuxer(muxerName)) { client, server ->
            val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS)
            val ping = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols, connection)
                .controller.get(5, TimeUnit.SECONDS)
            ping.ping().get(5, TimeUnit.SECONDS)
            connection.close().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            val direct = connection.muxerSession().createStream(Ping())
            assertPublicationClosed(direct)
            assertPublicationClosed(client.newStream(Ping().protocolDescriptor.announceProtocols, connection))
        }
    }
}

private fun publicationMuxer(name: String): StreamMuxerProtocol = when (name) {
    "yamux" -> StreamMuxerProtocol.getYamux()
    "mplex" -> StreamMuxerProtocol.Mplex
    else -> error("Unknown muxer: $name")
}

private fun observingPublicationMuxer(
    name: String,
    onActive: (Connection, ChannelHandlerContext) -> Unit
): StreamMuxerProtocol = StreamMuxerProtocol { multistream, protocols ->
    publicationMuxer(name).createMuxer(multistream, protocols).also { muxer ->
        (muxer as StreamMuxerDebug).muxFramesDebugHandler = ChannelVisitor { connection ->
            connection.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun channelActive(ctx: ChannelHandlerContext) {
                    onActive(connection, ctx)
                }
            })
        }
    }
}

private fun assertPublicationClosed(promise: StreamPromise<PingController>) {
    val controllerFailure = assertThrows(ExecutionException::class.java) {
        promise.controller.get(5, TimeUnit.SECONDS)
    }
    assertEquals(ConnectionClosedException::class.java, controllerFailure.cause!!.javaClass)
    val streamFailure = assertThrows(ExecutionException::class.java) {
        promise.stream.get(5, TimeUnit.SECONDS)
    }
    assertEquals(ConnectionClosedException::class.java, streamFailure.cause!!.javaClass)
}

private fun withPublicationHosts(
    clientMuxer: StreamMuxerProtocol,
    configure: (Host) -> Unit = {},
    body: (Host, Host) -> Unit
) {
    val client = host {
        identity { random() }
        transports { +::TcpTransport }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { +clientMuxer }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { +Ping() }
    }
    val server = host {
        identity { random() }
        transports { +::TcpTransport }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers {
            +StreamMuxerProtocol.getYamux()
            +StreamMuxerProtocol.Mplex
        }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { +Ping() }
    }
    var primary: Throwable? = null
    try {
        configure(client)
        server.start().get(5, TimeUnit.SECONDS)
        client.start().get(5, TimeUnit.SECONDS)
        body(client, server)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        val failures = mutableListOf<Throwable>()
        for (node in listOf(client, server)) {
            try {
                node.stop().get(5, TimeUnit.SECONDS)
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        if (primary != null) {
            failures.forEach { primary.addSuppressed(it) }
        } else if (failures.isNotEmpty()) {
            val failure = failures.first()
            failures.drop(1).forEach { failure.addSuppressed(it) }
            throw failure
        }
    }
}
