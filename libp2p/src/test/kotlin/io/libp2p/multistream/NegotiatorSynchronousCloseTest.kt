package io.libp2p.multistream

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.TestLogAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.SECONDS

class NegotiatorSynchronousCloseTest {
    @Test
    fun requesterHandlerIsAlreadyRemovedWhenInitiatorClosesDuringProtocolSetup() {
        assertSynchronousCloseDoesNotLogRemovalFailure(
            closeOnInitiator = true,
            closeOnResponder = false
        )
    }

    @Test
    fun responderHandlerIsAlreadyRemovedWhenResponderClosesDuringProtocolSetup() {
        assertSynchronousCloseDoesNotLogRemovalFailure(
            closeOnInitiator = false,
            closeOnResponder = true
        )
    }
}

private const val TEST_PROTOCOL = "/test/negotiator-synchronous-close/1.0.0"
private const val REQUESTER_HANDLER_MISSING =
    "io.libp2p.multistream.Negotiator\$RequesterHandler"
private const val RESPONDER_HANDLER_MISSING =
    "io.libp2p.multistream.Negotiator\$ResponderHandler"

private fun assertSynchronousCloseDoesNotLogRemovalFailure(
    closeOnInitiator: Boolean,
    closeOnResponder: Boolean
) {
    val server = host {
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { add(closeDuringProtocolSetup(closeOnResponder)) }
    }
    val client = host {
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { add(closeDuringProtocolSetup(closeOnInitiator)) }
    }

    TestLogAppender().install().use { logs ->
        try {
            server.start().get(5, SECONDS)
            client.start().get(5, SECONDS)

            val connection = client.network.connect(
                server.peerId,
                *server.listenAddresses().toTypedArray()
            ).get(5, SECONDS)
            val opening = client.newStream<Unit>(listOf(TEST_PROTOCOL), connection)

            val failure = assertThrows(ExecutionException::class.java) {
                opening.controller.get(5, SECONDS)
            }.cause
            assertEquals(ConnectionClosedException::class.java, failure?.javaClass)

            val missingHandlerExceptions = logs.logs.mapNotNull { it.thrown }
                .filter { it is NoSuchElementException }
                .filter { it.message == REQUESTER_HANDLER_MISSING || it.message == RESPONDER_HANDLER_MISSING }
            val expectedHandler = if (closeOnInitiator) REQUESTER_HANDLER_MISSING else RESPONDER_HANDLER_MISSING
            assertTrue(
                missingHandlerExceptions.none { it.message == expectedHandler },
                "Closing during selected-protocol setup must not log " +
                    "java.util.NoSuchElementException: $expectedHandler"
            )
        } finally {
            try {
                client.stop().get(5, SECONDS)
            } finally {
                server.stop().get(5, SECONDS)
            }
        }
    }
}

private fun closeDuringProtocolSetup(closeOnStart: Boolean): StrictProtocolBinding<Unit> =
    object : StrictProtocolBinding<Unit>(
        TEST_PROTOCOL,
        object : ProtocolHandler<Unit>(Long.MAX_VALUE, Long.MAX_VALUE) {
            override fun onStartInitiator(stream: Stream): CompletableFuture<Unit> =
                closeIfRequested(stream, closeOnStart)

            override fun onStartResponder(stream: Stream): CompletableFuture<Unit> =
                closeIfRequested(stream, closeOnStart)
        }
    ) {}

private fun closeIfRequested(stream: Stream, closeOnStart: Boolean): CompletableFuture<Unit> =
    if (closeOnStart) stream.close() else CompletableFuture.completedFuture(Unit)
