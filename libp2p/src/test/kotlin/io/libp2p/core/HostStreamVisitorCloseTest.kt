package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HostStreamVisitorCloseTest {
    @Test
    fun mplexVisitorCloseSettlesNegotiation() {
        checkVisitorCloseSettlesNegotiation(StreamMuxerProtocol.Mplex)
    }

    @Test
    fun yamuxVisitorCloseSettlesNegotiation() {
        checkVisitorCloseSettlesNegotiation(StreamMuxerProtocol.getYamux())
    }
}

private fun checkVisitorCloseSettlesNegotiation(muxer: StreamMuxerProtocol) {
    val client = host {
        muxers { +muxer }
        protocols { +Ping() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    val server = host {
        muxers { +muxer }
        protocols { +Ping() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    try {
        client.start().get(5, TimeUnit.SECONDS)
        server.start().get(5, TimeUnit.SECONDS)
        val root = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS)
        val protocols = Ping().protocolDescriptor.announceProtocols
        val first = client.newStream<PingController>(protocols, root)
        val firstController = first.controller.get(5, TimeUnit.SECONDS)
        val armed = AtomicBoolean(true)
        val reset = CompletableFuture<Stream>()
        val visitor = ChannelVisitor<Stream> { stream ->
            if (stream.isInitiator && stream.connection === root && armed.compareAndSet(true, false)) {
                stream.reset()
                reset.complete(stream)
            }
        }
        client.addStreamVisitor(visitor)
        try {
            val second = client.newStream<PingController>(protocols, root)
            val stream = second.stream.get(5, TimeUnit.SECONDS)
            assertTrue(stream === reset.get(5, TimeUnit.SECONDS), "The visitor must close this caller's exact stream")
            stream.closeFuture().get(5, TimeUnit.SECONDS)
            assertTrue(second.controller.isCompletedExceptionally,
                "A stream closed by its initialization visitor must fail its controller before it is returned to the caller")
            assertTrue(stream.getProtocol().isCompletedExceptionally,
                "A stream closed before negotiation must fail its protocol future")
            val message = "Channel closed before protocol negotiation: $stream"
            assertAll(
                { assertVisitorClosureCause(second.controller, message) },
                { assertVisitorClosureCause(stream.getProtocol(), message) }
            )
            assertFalse(root.closeFuture().isDone, "Closing one child must leave its shared parent live")
            assertTrue(firstController.ping().get(5, TimeUnit.SECONDS) >= 0,
                "The first accepted stream must still serve on the same parent")
            val replacement = client.newStream<PingController>(protocols, root)
            try {
                assertTrue(replacement.controller.get(5, TimeUnit.SECONDS).ping().get(5, TimeUnit.SECONDS) >= 0,
                    "The live parent must accept another stream after the reset")
            } finally {
                replacement.stream.get(5, TimeUnit.SECONDS).close().get(5, TimeUnit.SECONDS)
            }
        } finally {
            client.removeStreamVisitor(visitor)
            first.stream.get(5, TimeUnit.SECONDS).close().get(5, TimeUnit.SECONDS)
        }
    } finally {
        try { client.stop().get(5, TimeUnit.SECONDS) } finally { server.stop().get(5, TimeUnit.SECONDS) }
    }
}

private fun assertVisitorClosureCause(future: CompletableFuture<*>, message: String) {
    val failure = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
    assertEquals(ConnectionClosedException::class.java, failure.cause!!.javaClass)
    assertEquals(message, failure.cause!!.message)
}
