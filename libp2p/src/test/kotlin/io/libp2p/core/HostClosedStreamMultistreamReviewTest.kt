package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.multistream.MultistreamProtocolDebugV1
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostClosedStreamMultistreamReviewTest {
    @Test
    fun mplexClosedStreamDoesNotRunPreHandler() {
        closedStreamDoesNotRunPreHandler(StreamMuxerProtocol.Mplex)
    }

    @Test
    fun yamuxClosedStreamDoesNotRunPreHandler() {
        closedStreamDoesNotRunPreHandler(StreamMuxerProtocol.getYamux())
    }
}

private fun closedStreamDoesNotRunPreHandler(muxer: StreamMuxerProtocol) {
    val client = host {
        muxers { +muxer }
        protocols { +Ping() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    try {
        val server = host {
            muxers { +muxer }
            protocols { +Ping() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val promise = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols,
                client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS))
            val stream = promise.stream.get(5, TimeUnit.SECONDS)
            try {
                promise.controller.get(5, TimeUnit.SECONDS)
                val selected = stream.getProtocol().get(5, TimeUnit.SECONDS)
                stream.close().get(5, TimeUnit.SECONDS)
                val preCalls = AtomicInteger()
                val multistream = MultistreamProtocolDebugV1().copyWithHandlers(
                    preHandler = ChannelVisitor<Stream> { preCalls.incrementAndGet() }.toChannelHandler()
                ).createMultistream(listOf(Ping()))
                // Multistream.initChannel is public; closure is a real closed TCP child, not a flag.
                val result = multistream.initChannel(stream)
                assertTrue(result.isCompletedExceptionally, "Closed multistream must fail before initialization returns")
                val failure = assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }.cause!!
                assertEquals(ConnectionClosedException::class.java, failure.javaClass)
                assertEquals("Channel closed before protocol negotiation: $stream", failure.message)
                assertEquals(selected, stream.getProtocol().get(5, TimeUnit.SECONDS))
                assertEquals(0, preCalls.get(), "An already-closed stream must not run initialization callbacks")
            } finally {
                stream.close().get(5, TimeUnit.SECONDS)
            }
        } finally {
            server.stop().get(5, TimeUnit.SECONDS)
        }
    } finally {
        client.stop().get(5, TimeUnit.SECONDS)
    }
}
