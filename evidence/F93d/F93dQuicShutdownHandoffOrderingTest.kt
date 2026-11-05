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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93dQuicShutdownHandoffOrderingTest {
    @Test
    fun anOrdinaryCloseAlreadyInFlightHandsItsParentToTheWholeShutdownBarrier() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val firstResult = CompletableFuture<ChannelPromise>()
        val secondClose = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
        val firstParentRequests = AtomicInteger()
        var shutdown: CompletableFuture<Unit>? = null
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val address = server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey()))
            val first = client.dial(address, { }, null).get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            val second = client.dial(address, { }, null).get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            val firstParent = first.nettyChannel.parent()
            firstParent.pipeline().addFirst("count-first-parent-close", object : ChannelOutboundHandlerAdapter() {
                override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                    firstParentRequests.incrementAndGet()
                    ctx.close(promise)
                }
            })
            first.nettyChannel.pipeline().addFirst("hold-first-result", object : ChannelOutboundHandlerAdapter() {
                override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                    ctx.close(ctx.newPromise()).addListener { firstResult.complete(promise) }
                }
            })
            second.nettyChannel.pipeline().addFirst("hold-second-close", object : ChannelOutboundHandlerAdapter() {
                override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                    secondClose.complete(ctx to promise)
                }
            })
            first.nettyChannel.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            second.nettyChannel.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            val ordinary = first.close()
            firstResult.get(10, TimeUnit.SECONDS)
            val closing = client.close()
            shutdown = closing
            secondClose.get(10, TimeUnit.SECONDS)
            firstResult.get(10, TimeUnit.SECONDS).setSuccess()
            ordinary.get(10, TimeUnit.SECONDS)
            firstParent.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            assertEquals(0, firstParentRequests.get(), "An ordinary close must not release its parent while a sibling shutdown close is pending")
            assertFalse(closing.isDone)
            secondClose.get(10, TimeUnit.SECONDS).let { (ctx, promise) -> ctx.close(promise) }
            closing.get(10, TimeUnit.SECONDS)
            assertEquals(1, firstParentRequests.get())
            assertTrue(firstParent.closeFuture().isDone)
            assertFalse(firstParent.isOpen)
        } finally {
            try {
                firstResult.getNow(null)?.trySuccess()
                if (shutdown != null) {
                    secondClose.get(10, TimeUnit.SECONDS).let { (ctx, promise) -> if (!promise.isDone) ctx.close(promise) }
                    shutdown.get(10, TimeUnit.SECONDS)
                } else {
                    client.close().get(10, TimeUnit.SECONDS)
                }
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
