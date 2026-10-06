package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HostHandlerAddedCloseReviewTest {
    @Test
    fun mplexHandlerAddedCloseSettlesNegotiation() {
        checkHandlerAddedClose(StreamMuxerProtocol.Mplex)
    }

    @Test
    fun yamuxHandlerAddedCloseSettlesNegotiation() {
        checkHandlerAddedClose(StreamMuxerProtocol.getYamux())
    }
}

private fun checkHandlerAddedClose(muxer: StreamMuxerProtocol) {
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
        val closedInAdded = CompletableFuture<Stream>()
        val expectedCloseMessage = CompletableFuture<String>()
        val visitor = ChannelVisitor<Stream> { stream ->
            if (stream.isInitiator && stream.connection === root && armed.compareAndSet(true, false)) {
                // Registration defers handlerAdded until all initialization callbacks have returned.
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun handlerAdded(ctx: ChannelHandlerContext) {
                        stream.reset()
                        expectedCloseMessage.complete("Channel closed before protocol negotiation: $stream")
                        closedInAdded.complete(stream)
                    }
                })
            }
        }
        client.addStreamVisitor(visitor)
        try {
            val second = client.newStream<PingController>(protocols, root)
            val stream = second.stream.get(5, TimeUnit.SECONDS)
            assertTrue(stream === closedInAdded.get(5, TimeUnit.SECONDS), "handlerAdded must reset the caller's exact stream")
            stream.closeFuture().get(5, TimeUnit.SECONDS)
            assertFalse(root.closeFuture().isDone, "One child reset must leave the shared connection live")
            assertTrue(firstController.ping().get(5, TimeUnit.SECONDS) >= 0, "The first sibling must remain usable")
            val replacement = client.newStream<PingController>(protocols, root)
            try {
                assertTrue(replacement.controller.get(5, TimeUnit.SECONDS).ping().get(5, TimeUnit.SECONDS) >= 0,
                    "The same parent must accept a replacement stream")
            } finally {
                replacement.stream.get(5, TimeUnit.SECONDS).close().get(5, TimeUnit.SECONDS)
            }
            val message = expectedCloseMessage.get(5, TimeUnit.SECONDS)
            assertAll(
                { assertReviewClosureFailure(second.controller, message, "controller") },
                { assertReviewClosureFailure(stream.getProtocol(), message, "selected protocol") }
            )
        } finally {
            client.removeStreamVisitor(visitor)
            first.stream.get(5, TimeUnit.SECONDS).close().get(5, TimeUnit.SECONDS)
        }
    } finally {
        try { client.stop().get(5, TimeUnit.SECONDS) } finally { server.stop().get(5, TimeUnit.SECONDS) }
    }
}

private fun assertReviewClosureFailure(future: CompletableFuture<*>, message: String, label: String) {
    assertTrue(future.isCompletedExceptionally,
        "The $label future must fail when handlerAdded closes the child before negotiation; done=${future.isDone}")
    val failure = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
    assertEquals(ConnectionClosedException::class.java, failure.cause!!.javaClass)
    assertEquals(message, failure.cause!!.message)
}
