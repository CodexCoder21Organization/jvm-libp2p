package io.libp2p.network

import io.libp2p.core.*
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class NetworkProvisionalStreamOutcomeTest {
    @ParameterizedTest
    @CsvSource(
        "false,success", "true,success",
        "false,early-cancel", "true,early-cancel",
        "false,negotiation-failure", "true,negotiation-failure",
        "false,released-hold", "true,released-hold",
        "false,queued-cancel", "true,queued-cancel",
        "false,initialized-cancel", "true,initialized-cancel",
        "false,parent-close", "true,parent-close"
    )
    fun bothResultsSettleOnOtherCreationPaths(mplex: Boolean, outcome: String) {
        val entered = CompletableFuture<Unit>()
        val initialization = CompletableFuture<Unit>()
        val earlyParent = CompletableFuture<Connection>()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val protocol = "/test/stream-outcome/1.0.0"
        val binding = object : StrictProtocolBinding<Unit>(
            protocol,
            object : ProtocolHandler<Unit>(1, 1) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Unit> {
                    entered.complete(Unit)
                    return if (outcome == "initialized-cancel") initialization else CompletableFuture.completedFuture(Unit)
                }
                override fun onStartResponder(stream: Stream) = CompletableFuture.completedFuture(Unit)
            }
        ) {}
        val muxer = if (mplex) StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        val server = host {
            network { listen("/ip4/127.0.0.1/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { +muxer }
            protocols { if (outcome != "negotiation-failure") +binding }
        }
        val client = host {
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { +muxer }
            protocols { +binding }
        }
        try {
            server.start().awaitProvisionalOutcome("Server startup", 10)
            client.start().awaitProvisionalOutcome("Client startup", 10)
            if (outcome == "early-cancel") {
                client.addConnectionHandler(ConnectionHandler.create { connection ->
                    earlyParent.complete(connection)
                    check(release.await(10, SECONDS)) { "The test must release parent publication before stream creation" }
                })
            }
            val earlyChild = if (outcome == "early-cancel") {
                client.newStream<Unit>(listOf(protocol), server.peerId, server.listenAddresses().single())
            } else {
                null
            }
            val parent = if (earlyChild != null) {
                earlyParent.awaitProvisionalOutcome("Parent before stream creation is queued")
            } else {
                client.network.connect(server.peerId, server.listenAddresses().single()).awaitProvisionalOutcome("Parent dial")
            }
            val hold = checkNotNull(client.network.holdConnection(parent).awaitProvisionalOutcome("Parent hold"))
            if (outcome == "released-hold") hold.close()
            if (outcome == "queued-cancel" || outcome == "parent-close") {
                (parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {
                    blocked.countDown()
                    check(release.await(10, SECONDS)) { "The test must release queued child creation for $outcome" }
                }
                assertTrue(blocked.await(5, SECONDS), "The event loop must be held before creating the $outcome child")
            }
            val child = earlyChild ?: client.newStream<Unit>(listOf(protocol), hold)
            when (outcome) {
                "success" -> {
                    val stream = child.stream.awaitProvisionalOutcome("Successful child stream")
                    assertSame(parent, stream.connection)
                    assertEquals(Unit, child.controller.awaitProvisionalOutcome("Successful child controller"))
                    stream.close().awaitProvisionalOutcome("Successful child close")
                }
                "early-cancel" -> {
                    assertTrue(child.controller.cancel(false), "The ordinary caller must cancel before parent publication queues stream creation")
                    release.countDown()
                    val stream = child.stream.awaitProvisionalOutcome("Ordinary stream after early caller cancellation")
                    assertSame(parent, stream.connection)
                    assertTrue(child.controller.isCancelled, "Later creation must preserve the ordinary caller's cancellation")
                    stream.close().awaitProvisionalOutcome("Ordinary child close after early caller cancellation")
                }
                "negotiation-failure" -> {
                    val stream = child.stream.awaitProvisionalOutcome("Stream before protocol rejection")
                    val failure = child.controller.handle { _, cause -> cause }.awaitProvisionalOutcome("Rejected protocol controller")
                    assertInstanceOf(NoSuchRemoteProtocolException::class.java, failure)
                    stream.closeFuture().awaitProvisionalOutcome("Rejected child close")
                    (parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.awaitProvisionalOutcome("Protocol rejection cleanup")
                    assertFalse(hold.promote(), "Protocol rejection must release the caller hold")
                }
                "released-hold" -> {
                    for (future in listOf(child.stream, child.controller)) {
                        val failure = future.handle { _, cause -> cause }.awaitProvisionalOutcome("Released hold result")
                        assertInstanceOf(ConnectionClosedException::class.java, failure)
                        assertEquals(
                            "Cannot open a provisional stream because its hold was released or its connection is closed or excluded from selection: $parent",
                            failure.message
                        )
                    }
                }
                "queued-cancel" -> {
                    assertTrue(child.controller.cancel(false), "Cancellation must settle the controller before queued creation runs")
                    val failure = child.stream.handle { _, cause -> cause }.awaitProvisionalOutcome("Queued cancellation stream result")
                    assertInstanceOf(CancellationException::class.java, if (failure is CompletionException) failure.cause else failure)
                    release.countDown()
                    assertTrue(child.controller.isCancelled)
                    assertFalse(hold.promote(), "Queued cancellation must release the caller hold")
                }
                "initialized-cancel" -> {
                    val stream = child.stream.awaitProvisionalOutcome("Initialized child stream")
                    entered.awaitProvisionalOutcome("Initiator initialization")
                    assertTrue(child.controller.cancel(false), "The pending initialized controller must accept cancellation")
                    stream.closeFuture().awaitProvisionalOutcome("Cancelled initialized child close")
                    assertSame(stream, child.stream.awaitProvisionalOutcome("Preserved published stream result"))
                    assertTrue(child.controller.isCancelled)
                    assertFalse(hold.promote(), "Initialized cancellation must release the caller hold")
                }
                "parent-close" -> {
                    val close = parent.close()
                    release.countDown()
                    close.awaitProvisionalOutcome("Parent close")
                    for (future in listOf(child.stream, child.controller)) {
                        val failure = future.handle { _, cause -> cause }.awaitProvisionalOutcome("Parent-closed child result")
                        assertInstanceOf(ConnectionClosedException::class.java, if (failure is CompletionException) failure.cause else failure)
                    }
                    assertFalse(hold.promote(), "A closed parent cannot be retained")
                }
                else -> fail<Unit>("Unknown stream creation outcome: $outcome")
            }
            hold.close()
            hold.close()
        } finally {
            release.countDown()
            initialization.complete(Unit)
            try {
                client.stop().awaitProvisionalOutcome("Client shutdown", 10)
            } finally {
                server.stop().awaitProvisionalOutcome("Server shutdown", 10)
            }
        }
    }
}

private fun <T> Future<T>.awaitProvisionalOutcome(step: String, seconds: Long = 5): T =
    try {
        get(seconds, SECONDS)
    } catch (failure: TimeoutException) {
        throw AssertionError("$step did not finish within $seconds seconds", failure)
    }
