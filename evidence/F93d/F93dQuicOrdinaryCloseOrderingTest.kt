package io.libp2p.transport

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

class F93dQuicOrdinaryCloseOrderingTest {
    @Test
    fun shutdownWaitsForAnOrdinaryChildCloseThatWasAlreadyPhysicallyRemoved() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val pendingChild = CompletableFuture<ChannelPromise>()
        val parentRequests = AtomicInteger()
        val failure = IOException("The ordinary child close reports failure only after physical closure")
        var ordinary: CompletableFuture<Unit>? = null
        var shutdown: CompletableFuture<Unit>? = null
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val connection = client.dial(server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey())), { }, null)
                .get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            val child = connection.nettyChannel
            val parent = child.parent()
            parent.pipeline().addFirst("count-parent-close", object : ChannelOutboundHandlerAdapter() {
                override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                    parentRequests.incrementAndGet()
                    ctx.close(promise)
                }
            })
            child.pipeline().addFirst("hold-ordinary-child-result", object : ChannelOutboundHandlerAdapter() {
                override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                    ctx.close(ctx.newPromise()).addListener { pendingChild.complete(promise) }
                }
            })
            child.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            ordinary = connection.close()
            val result = pendingChild.get(10, TimeUnit.SECONDS)
            child.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            assertFalse(child.isOpen)
            assertEquals(0, client.activeConnections)
            assertFalse(result.isDone)
            shutdown = client.close()
            parent.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            assertEquals(0, parentRequests.get(), "Shutdown must await the original child operation even after physical removal")
            assertFalse(shutdown.isDone, "Shutdown must preserve ownership of the pending ordinary close")
            result.setFailure(failure)
            val ordinaryError = assertThrows(ExecutionException::class.java) { ordinary.get(10, TimeUnit.SECONDS) }
            val shutdownError = assertThrows(ExecutionException::class.java) { shutdown.get(10, TimeUnit.SECONDS) }
            assertSame(failure, ordinaryError.cause)
            assertSame(failure, shutdownError.cause)
            assertEquals(1, parentRequests.get())
            assertTrue(parent.closeFuture().isDone)
            assertFalse(parent.isOpen)
        } finally {
            try {
                pendingChild.getNow(null)?.tryFailure(failure)
                if (shutdown == null) client.close().handle { _, _ -> Unit }.get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
