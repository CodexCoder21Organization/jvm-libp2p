package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93bQuicRepeatedCloseOrderingTest {
    @Test
    fun repeatedShutdownCannotCloseUdpParentBeforeTheFirstChildOperationSettles() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val accepted = CompletableFuture<Connection>()
        val pendingChild = CompletableFuture<ChannelPromise>()
        val pendingParent = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
        val parentCloseRequests = AtomicInteger()
        val failure = IOException("The first child close operation reports its failure after physical closure")
        var firstClose: CompletableFuture<Unit>? = null
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { accepted.complete(it) }, null).get(10, TimeUnit.SECONDS)
            val connection =
                client.dial(server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey())), { }, null)
                    .get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            accepted.get(10, TimeUnit.SECONDS)
            val child = connection.nettyChannel
            val parent = child.parent()
            parent.pipeline().addFirst(
                "hold-udp-close",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(
                        ctx: ChannelHandlerContext,
                        promise: ChannelPromise
                    ) {
                        parentCloseRequests.incrementAndGet()
                        pendingParent.complete(ctx to promise)
                    }
                }
            )
            child.pipeline().addFirst(
                "hold-child-result-after-physical-close",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(
                        ctx: ChannelHandlerContext,
                        promise: ChannelPromise
                    ) {
                        ctx.close(ctx.newPromise()).addListener { pendingChild.complete(promise) }
                    }
                }
            )
            child.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            firstClose = client.close()
            val childPromise = pendingChild.get(10, TimeUnit.SECONDS)
            child.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            assertFalse(child.isOpen, "The first child must already be physically closed")
            assertEquals(0, client.activeConnections, "Physical close must have removed the child from the live set")
            assertFalse(childPromise.isDone, "Its original close operation must still be pending")
            assertFalse(firstClose.isDone, "The original shutdown must await that operation")
            client.close()
            parent.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            assertEquals(
                0,
                parentCloseRequests.get(),
                "A repeated shutdown must not start UDP-parent close before the first child-close operation settles"
            )
        } finally {
            try {
                if (firstClose != null) {
                    pendingChild.get(10, TimeUnit.SECONDS).tryFailure(failure)
                    val (context, promise) = pendingParent.get(10, TimeUnit.SECONDS)
                    context.close(promise)
                    val error = assertThrows(ExecutionException::class.java) { firstClose.get(10, TimeUnit.SECONDS) }
                    assertSame(failure, error.cause, "Cleanup must retain the deliberate first-child failure")
                } else {
                    client.close().get(10, TimeUnit.SECONDS)
                }
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
