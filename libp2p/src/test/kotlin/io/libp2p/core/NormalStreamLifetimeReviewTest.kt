package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.implementation.P2PChannelOverNetty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NormalStreamLifetimeReviewTest {
    @Test
    fun mplexNormalClosurePreservesResultsAndReleasesDependents() {
        reviewNormalStreamLifetime(StreamMuxerProtocol.Mplex)
    }

    @Test
    fun yamuxNormalClosurePreservesResultsAndReleasesDependents() {
        reviewNormalStreamLifetime(StreamMuxerProtocol.getYamux())
    }
}

private fun reviewNormalStreamLifetime(muxer: StreamMuxerProtocol) {
    val client = host {
        muxers { +muxer }
        protocols { +Echo() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    val server = host {
        muxers { +muxer }
        protocols { +Echo() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    try {
        client.start().get(5, TimeUnit.SECONDS)
        server.start().get(5, TimeUnit.SECONDS)
        val parent = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
            .get(5, TimeUnit.SECONDS)
        val parentClose = parent.closeFuture()
        val parentDependents = parentClose.numberOfDependents
        val protocols = Echo().protocolDescriptor.announceProtocols
        // Each cycle checks for residue left by the preceding cycle on the same live parent.
        repeat(128) { index ->
            val promise = client.newStream<EchoController>(protocols, parent)
            val stream = promise.stream.get(5, TimeUnit.SECONDS)
            val channel = (stream as P2PChannelOverNetty).nettyChannel
            try {
                val controller = promise.controller.get(5, TimeUnit.SECONDS)
                val protocol = stream.getProtocol().get(5, TimeUnit.SECONDS)
                assertEquals(protocols.single(), protocol)
                assertEquals("cycle=$index", controller.echo("cycle=$index").get(5, TimeUnit.SECONDS))
                val controllerNotifications = AtomicInteger()
                val protocolNotifications = AtomicInteger()
                promise.controller.whenComplete { _, _ -> controllerNotifications.incrementAndGet() }
                stream.getProtocol().whenComplete { _, _ -> protocolNotifications.incrementAndGet() }
                channel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
                assertFalse(channel.pipeline().names().any { it.startsWith("ProtocolSelect") }, "cycle=$index")
                val childClose = stream.closeFuture()
                assertSame(childClose, stream.closeFuture())
                assertEquals(2, childClose.numberOfDependents, "Host removal plus one negotiation observer while live; cycle=$index")
                assertEquals(parentDependents, parentClose.numberOfDependents, "No parent future accumulation; cycle=$index")
                stream.close().get(5, TimeUnit.SECONDS)
                childClose.get(5, TimeUnit.SECONDS)
                // The barrier observes completion of close listeners, rather than merely the future's result publication.
                channel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
                assertEquals(0, childClose.numberOfDependents, "Closed child must retain no observer; cycle=$index")
                assertFalse(client.streams.contains(stream), "Host stream ownership released; cycle=$index")
                assertTrue(channel.pipeline().names().filterNot { it.contains("TailContext") }.isEmpty(), "cycle=$index")
                assertSame(controller, promise.controller.get(5, TimeUnit.SECONDS))
                assertEquals(protocol, stream.getProtocol().get(5, TimeUnit.SECONDS))
                assertEquals(1, controllerNotifications.get(), "cycle=$index")
                assertEquals(1, protocolNotifications.get(), "cycle=$index")
                assertEquals(parentDependents, parentClose.numberOfDependents, "cycle=$index")
                assertFalse(parentClose.isDone)
            } finally {
                stream.close().get(5, TimeUnit.SECONDS)
            }
        }
    } finally {
        try { client.stop().get(5, TimeUnit.SECONDS) } finally { server.stop().get(5, TimeUnit.SECONDS) }
    }
}
