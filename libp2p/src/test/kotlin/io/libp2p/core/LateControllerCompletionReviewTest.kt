package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.transport.implementation.P2PChannelOverNetty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LateControllerCompletionReviewTest {
    @Test
    fun mplexLateControllerSuccessCannotReplaceClosureFailure() {
        reviewLateControllerCompletion(StreamMuxerProtocol.Mplex)
    }

    @Test
    fun yamuxLateControllerSuccessCannotReplaceClosureFailure() {
        reviewLateControllerCompletion(StreamMuxerProtocol.getYamux())
    }
}

private fun reviewLateControllerCompletion(muxer: StreamMuxerProtocol) {
    val protocol = "/late-controller-review/1.0.0"
    val pending = CompletableFuture<Unit>()
    val initialized = CompletableFuture<P2PChannel>()
    val clientProtocol = object : ProtocolBinding<Unit> {
        override val protocolDescriptor = ProtocolDescriptor(protocol)
        override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<Unit> {
            initialized.complete(ch)
            return pending
        }
    }
    val serverProtocol = object : ProtocolBinding<Unit> {
        override val protocolDescriptor = ProtocolDescriptor(protocol)
        override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<Unit> =
            CompletableFuture.completedFuture(Unit)
    }
    val client = host {
        muxers { +muxer }
        protocols { +clientProtocol }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    val server = host {
        muxers { +muxer }
        protocols { +serverProtocol }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    try {
        client.start().get(5, TimeUnit.SECONDS)
        server.start().get(5, TimeUnit.SECONDS)
        val parent = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
            .get(5, TimeUnit.SECONDS)
        val promise = client.newStream<Unit>(listOf(protocol), parent)
        val stream = promise.stream.get(5, TimeUnit.SECONDS)
        try {
            assertSame(stream, initialized.get(5, TimeUnit.SECONDS))
            assertEquals(protocol, stream.getProtocol().get(5, TimeUnit.SECONDS))
            assertFalse(promise.controller.isDone)
            val controllerNotifications = AtomicInteger()
            val protocolNotifications = AtomicInteger()
            val reentered = CompletableFuture<Unit>()
            stream.getProtocol().whenComplete { _, _ -> protocolNotifications.incrementAndGet() }
            promise.controller.whenComplete { _, _ ->
                controllerNotifications.incrementAndGet()
                stream.reset()
                reentered.complete(Unit)
            }
            stream.close().get(5, TimeUnit.SECONDS)
            reentered.get(5, TimeUnit.SECONDS)
            val channel = (stream as P2PChannelOverNetty).nettyChannel
            channel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
            val failure = assertThrows(ExecutionException::class.java) { promise.controller.get(5, TimeUnit.SECONDS) }
            assertEquals(ConnectionClosedException::class.java, failure.cause!!.javaClass)
            assertEquals("Channel closed $channel", failure.cause!!.message)
            assertTrue(pending.complete(Unit))
            val late = assertThrows(ExecutionException::class.java) { promise.controller.get(5, TimeUnit.SECONDS) }
            assertSame(failure.cause, late.cause)
            assertEquals(protocol, stream.getProtocol().get(5, TimeUnit.SECONDS))
            assertEquals(1, controllerNotifications.get())
            assertEquals(1, protocolNotifications.get())
            assertEquals(0, stream.closeFuture().numberOfDependents)
            assertFalse(parent.closeFuture().isDone)
        } finally {
            stream.close().get(5, TimeUnit.SECONDS)
        }
    } finally {
        try { client.stop().get(5, TimeUnit.SECONDS) } finally { server.stop().get(5, TimeUnit.SECONDS) }
    }
}
