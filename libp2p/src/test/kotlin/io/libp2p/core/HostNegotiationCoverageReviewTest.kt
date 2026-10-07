package io.libp2p.core

import io.libp2p.core.dsl.Builder
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.MultistreamProtocolDebug
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.multistream.MultistreamProtocolDebugV1
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostNegotiationCoverageReviewTest {
    @Test
    fun mplexSelectedProtocolSurvivesClose() = selectedProtocolSurvivesClose(StreamMuxerProtocol.Mplex)
    @Test
    fun yamuxSelectedProtocolSurvivesClose() = selectedProtocolSurvivesClose(StreamMuxerProtocol.getYamux())
    @Test
    fun mplexSelectedProtocolWithPendingControllerSurvivesClose() = selectedProtocolWithPendingControllerSurvivesClose(StreamMuxerProtocol.Mplex)
    @Test
    fun yamuxSelectedProtocolWithPendingControllerSurvivesClose() = selectedProtocolWithPendingControllerSurvivesClose(StreamMuxerProtocol.getYamux())
    @Test
    fun mplexCloseOnFirstNegotiationMessage() = closeOnFirstNegotiationMessage(StreamMuxerProtocol.Mplex)
    @Test
    fun yamuxCloseOnFirstNegotiationMessage() = closeOnFirstNegotiationMessage(StreamMuxerProtocol.getYamux())
    @Test
    fun mplexResponderVisitorReset() = responderVisitorReset(StreamMuxerProtocol.Mplex)
    @Test
    fun yamuxResponderVisitorReset() = responderVisitorReset(StreamMuxerProtocol.getYamux())
    @Test
    fun mplexCompletionRemainsTerminal() = completionRemainsTerminal(StreamMuxerProtocol.Mplex)
    @Test
    fun yamuxCompletionRemainsTerminal() = completionRemainsTerminal(StreamMuxerProtocol.getYamux())
    @Test
    fun mplexSeveralSiblingsSurviveReset() = severalSiblingsSurviveReset(StreamMuxerProtocol.Mplex)
    @Test
    fun yamuxSeveralSiblingsSurviveReset() = severalSiblingsSurviveReset(StreamMuxerProtocol.getYamux())
    @Test
    fun mplexPreHandlerCloseStopsPostHandler() = preHandlerCloseStopsPostHandler(StreamMuxerProtocol.Mplex)
    @Test
    fun yamuxPreHandlerCloseStopsPostHandler() = preHandlerCloseStopsPostHandler(StreamMuxerProtocol.getYamux())
    @Test
    fun mplexPostHandlerCloseSettlesFutures() = postHandlerCloseSettlesFutures(StreamMuxerProtocol.Mplex)
    @Test
    fun yamuxPostHandlerCloseSettlesFutures() = postHandlerCloseSettlesFutures(StreamMuxerProtocol.getYamux())
}

private fun selectedProtocolSurvivesClose(muxer: StreamMuxerProtocol) {
    withReviewHosts(muxer) { client, server ->
        val promise = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols,
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS))
        val stream = promise.stream.get(5, TimeUnit.SECONDS)
        val controller = promise.controller.get(5, TimeUnit.SECONDS)
        val protocol = stream.getProtocol().get(5, TimeUnit.SECONDS)
        val protocolCompletions = AtomicInteger()
        val controllerCompletions = AtomicInteger()
        stream.getProtocol().whenComplete { _, _ -> protocolCompletions.incrementAndGet() }
        promise.controller.whenComplete { _, _ -> controllerCompletions.incrementAndGet() }
        assertTrue(controller.ping().get(5, TimeUnit.SECONDS) >= 0)
        stream.reset().get(5, TimeUnit.SECONDS)
        assertEquals(protocol, stream.getProtocol().get(5, TimeUnit.SECONDS), "Selected protocol success is terminal")
        assertSame(controller, promise.controller.get(5, TimeUnit.SECONDS), "Accepted controller success is terminal")
        assertEquals(1, protocolCompletions.get())
        assertEquals(1, controllerCompletions.get())
    }
}

private fun selectedProtocolWithPendingControllerSurvivesClose(muxer: StreamMuxerProtocol) {
    val initialized = CompletableFuture<Unit>()
    val pending = CompletableFuture<String>()
    val protocol = "/review-pending-controller/1.0.0"
    val clientBinding = object : ProtocolBinding<String> {
        override val protocolDescriptor = ProtocolDescriptor(protocol)
        override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<String> {
            initialized.complete(Unit)
            return pending
        }
    }
    val serverBinding = ProtocolBinding.createSimple(protocol, P2PChannelHandler { CompletableFuture.completedFuture(Unit) })
    withReviewHosts(muxer, configureClient = { protocols { +clientBinding } },
        configureServer = { protocols { +serverBinding } }) { client, server ->
        val promise = client.newStream<String>(listOf(protocol),
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS))
        val stream = promise.stream.get(5, TimeUnit.SECONDS)
        initialized.get(5, TimeUnit.SECONDS)
        assertEquals(protocol, stream.getProtocol().get(5, TimeUnit.SECONDS))
        assertFalse(promise.controller.isDone)
        stream.reset().get(5, TimeUnit.SECONDS)
        val message = "Channel closed ${(stream as P2PChannelOverNetty).nettyChannel}"
        val cause = reviewFailure(promise.controller, message)
        assertEquals(protocol, stream.getProtocol().get(5, TimeUnit.SECONDS), "Selection remains successful when controller setup is pending")
        pending.complete("late controller")
        assertSame(cause, reviewFailure(promise.controller, message))
    }
}

private fun closeOnFirstNegotiationMessage(muxer: StreamMuxerProtocol) {
    val firstMessage = CompletableFuture<String>()
    withReviewHosts(muxer, configureClient = {
        debug { streamPreHandler.addHandler { stream ->
            stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    try {
                        assertFalse(stream.getProtocol().isDone, "Close must precede selection")
                        val bytes = msg as ByteBuf
                        val wire = bytes.toString(Charsets.UTF_8)
                        assertEquals("/multistream/1.0.0\n", wire.substring(1), "The first received message is the negotiation header")
                        stream.reset()
                        firstMessage.complete("Channel closed ${ctx.channel()}")
                    } catch (failure: Throwable) {
                        firstMessage.completeExceptionally(failure)
                        throw failure
                    } finally {
                        ReferenceCountUtil.release(msg)
                    }
                }
            })
        } }
    }) { client, server ->
        val promise = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols,
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS))
        val stream = promise.stream.get(5, TimeUnit.SECONDS)
        val message = firstMessage.get(5, TimeUnit.SECONDS)
        stream.closeFuture().get(5, TimeUnit.SECONDS)
        assertAll({ reviewFailure(promise.controller, message) }, { reviewFailure(stream.getProtocol(), message) })
    }
}

private fun responderVisitorReset(muxer: StreamMuxerProtocol) {
    val inbound = CompletableFuture<Stream>()
    val captured = CompletableFuture<CompletableFuture<*>>()
    withReviewHosts(muxer, configureServer = {
        streamMultistreamProtocol = RecordingMultistream(MultistreamProtocolDebugV1(), captured)
    }) { client, server ->
        server.addStreamVisitor { stream ->
            assertFalse(stream.isInitiator)
            stream.reset()
            inbound.complete(stream)
        }
        val promise = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols,
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS))
        promise.stream.get(5, TimeUnit.SECONDS)
        val stream = inbound.get(5, TimeUnit.SECONDS)
        val responderController = captured.get(5, TimeUnit.SECONDS)
        stream.closeFuture().get(5, TimeUnit.SECONDS)
        val message = "Channel closed before protocol negotiation: $stream"
        assertAll({ reviewFailure(responderController, message) }, { reviewFailure(stream.getProtocol(), message) })
        assertFalse(stream.connection.closeFuture().isDone)
    }
}

private fun completionRemainsTerminal(muxer: StreamMuxerProtocol) {
    val completedProtocol = CompletableFuture<Throwable>()
    val protocolCompletions = AtomicInteger()
    withReviewHosts(muxer) { client, server ->
        client.addStreamVisitor { stream ->
            stream.getProtocol().whenComplete { _, failure ->
                protocolCompletions.incrementAndGet()
                completedProtocol.complete(failure)
            }
            // Closure runs the observer now; MultistreamImpl checks the same closure after return.
            stream.reset()
        }
        val promise = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols,
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS))
        val stream = promise.stream.get(5, TimeUnit.SECONDS)
        val controllerCompletions = AtomicInteger()
        val completedController = CompletableFuture<Throwable>()
        promise.controller.whenComplete { _, failure ->
            controllerCompletions.incrementAndGet()
            completedController.complete(failure)
        }
        val message = "Channel closed before protocol negotiation: $stream"
        val protocolFailure = reviewFailure(stream.getProtocol(), message)
        val controllerFailure = reviewFailure(promise.controller, message)
        assertSame(completedProtocol.get(5, TimeUnit.SECONDS), protocolFailure, "Later settlement cannot replace the first result")
        assertSame(protocolFailure, controllerFailure, "Both futures retain the one closure cause")
        assertSame(controllerFailure, completedController.get(5, TimeUnit.SECONDS))
        stream.reset().get(5, TimeUnit.SECONDS)
        assertSame(protocolFailure, reviewFailure(stream.getProtocol(), message))
        assertSame(controllerFailure, reviewFailure(promise.controller, message))
        assertEquals(1, protocolCompletions.get())
        assertEquals(1, controllerCompletions.get())
    }
}

private fun severalSiblingsSurviveReset(muxer: StreamMuxerProtocol) {
    withReviewHosts(muxer) { client, server ->
        val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS)
        // Sequential creation keeps all three accepted siblings live before the visitor is armed.
        val siblings = (1..3).map {
            client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols, connection)
                .controller.get(5, TimeUnit.SECONDS)
        }
        val visitor = ChannelVisitor<Stream> { it.reset() }
        client.addStreamVisitor(visitor)
        try {
            val victim = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols, connection)
            val stream = victim.stream.get(5, TimeUnit.SECONDS)
            stream.closeFuture().get(5, TimeUnit.SECONDS)
            val message = "Channel closed before protocol negotiation: $stream"
            assertAll({ reviewFailure(victim.controller, message) }, { reviewFailure(stream.getProtocol(), message) })
            assertFalse(connection.closeFuture().isDone)
            siblings.forEach { assertTrue(it.ping().get(5, TimeUnit.SECONDS) >= 0) }
        } finally {
            client.removeStreamVisitor(visitor)
        }
        val replacement = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols, connection)
        assertTrue(replacement.controller.get(5, TimeUnit.SECONDS).ping().get(5, TimeUnit.SECONDS) >= 0)
    }
}

private fun preHandlerCloseStopsPostHandler(muxer: StreamMuxerProtocol) {
    val postCalls = AtomicInteger()
    withReviewHosts(muxer, configureClient = {
        debug { streamHandler.addHandler { postCalls.incrementAndGet() } }
    }) { client, server ->
        client.addStreamVisitor { it.reset() }
        val promise = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols,
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS))
        val stream = promise.stream.get(5, TimeUnit.SECONDS)
        stream.closeFuture().get(5, TimeUnit.SECONDS)
        val message = "Channel closed before protocol negotiation: $stream"
        assertAll({ reviewFailure(promise.controller, message) }, { reviewFailure(stream.getProtocol(), message) })
        assertEquals(0, postCalls.get(), "Initialization callbacks stop after the pre-handler closes the stream")
    }
}

private fun postHandlerCloseSettlesFutures(muxer: StreamMuxerProtocol) {
    val postClosed = CompletableFuture<Stream>()
    withReviewHosts(muxer, configureClient = {
        debug { streamHandler.addHandler { stream ->
            stream.reset()
            postClosed.complete(stream)
        } }
    }) { client, server ->
        val promise = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols,
            client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS))
        val stream = promise.stream.get(5, TimeUnit.SECONDS)
        assertSame(stream, postClosed.get(5, TimeUnit.SECONDS))
        stream.closeFuture().get(5, TimeUnit.SECONDS)
        val message = "Channel closed before protocol negotiation: $stream"
        assertAll({ reviewFailure(promise.controller, message) }, { reviewFailure(stream.getProtocol(), message) })
    }
}

private fun reviewFailure(future: CompletableFuture<*>, message: String): Throwable {
    assertTrue(future.isCompletedExceptionally, "Closed negotiation must fail immediately")
    val failure = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }.cause!!
    assertEquals(ConnectionClosedException::class.java, failure.javaClass)
    assertEquals(message, failure.message)
    return failure
}

private fun withReviewHosts(
    muxer: StreamMuxerProtocol,
    configureClient: Builder.() -> Unit = {},
    configureServer: Builder.() -> Unit = {},
    body: (Host, Host) -> Unit
) {
    val streams = CopyOnWriteArrayList<Stream>()
    val client = host {
        muxers { +muxer }
        protocols { +Ping() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        configureClient()
    }
    try {
        val server = host {
            muxers { +muxer }
            protocols { +Ping() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            configureServer()
        }
        try {
            client.addStreamVisitor { streams.add(it) }
            server.addStreamVisitor { streams.add(it) }
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            try {
                body(client, server)
            } finally {
                closeReviewStreams(streams.toList())
            }
        } finally {
            server.stop().get(5, TimeUnit.SECONDS)
        }
    } finally {
        client.stop().get(5, TimeUnit.SECONDS)
    }
}

private fun closeReviewStreams(streams: List<Stream>) {
    if (streams.isEmpty()) return
    try {
        streams.first().close().get(5, TimeUnit.SECONDS)
    } finally {
        closeReviewStreams(streams.drop(1))
    }
}

// Decorates the public multistream interface and retains the responder's returned controller future.
private class RecordingMultistream(
    private val delegate: MultistreamProtocolDebug,
    private val captured: CompletableFuture<CompletableFuture<*>>
) : MultistreamProtocolDebug {
    override val version get() = delegate.version
    override fun <TController> createMultistream(bindings: List<ProtocolBinding<TController>>): Multistream<TController> {
        val original = delegate.createMultistream(bindings)
        return object : Multistream<TController> {
            override val bindings = original.bindings
            override fun initChannel(ch: P2PChannel): CompletableFuture<TController> = original.initChannel(ch).also {
                captured.complete(it)
            }
        }
    }
    override fun copyWithHandlers(preHandler: P2PChannelHandler<*>?, postHandler: P2PChannelHandler<*>?): MultistreamProtocol =
        RecordingMultistream(delegate.copyWithHandlers(preHandler, postHandler) as MultistreamProtocolDebug, captured)
}
