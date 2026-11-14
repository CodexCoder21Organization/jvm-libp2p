package io.libp2p.transport

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.util.concurrent.ImmediateEventExecutor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class R93ADestroyPendingReleaseTest {
    @Test
    fun externalLoopTerminationDrainsPendingDialRelease() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val addressSeen = CompletableFuture<InetSocketAddress>()
        val unregisterEntered = CompletableFuture<Unit>()
        val releaseUnregister = CountDownLatch(1)
        val parentSeen = CompletableFuture<io.netty.channel.Channel>()
        val expected = IllegalStateException("The public dial visitor rejects this connection")
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val dial = client.dial(
                server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey())),
                { fail<Unit>("A rejected dial must not publish a connection") },
                { connection ->
                    val parent = (connection as ConnectionOverNetty).nettyChannel.parent()
                    parentSeen.complete(parent)
                    addressSeen.complete(parent.localAddress() as InetSocketAddress)
                    parent.pipeline().addLast(object : ChannelOutboundHandlerAdapter() {
                        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                            val physical = DefaultChannelPromise(ctx.channel(), ImmediateEventExecutor.INSTANCE)
                            physical.addListener { result ->
                                check(result.isSuccess) { "The fixture must physically close its UDP parent" }
                                promise.trySuccess()
                                unregisterEntered.complete(Unit)
                                check(releaseUnregister.await(10, TimeUnit.SECONDS)) { "The fixture must release UDP deregistration" }
                            }
                            ctx.executor().execute { ctx.close(physical) }
                        }
                    })
                    throw expected
                }
            )
            val bound = addressSeen.get(10, TimeUnit.SECONDS)
            unregisterEntered.get(10, TimeUnit.SECONDS)
            assertThrows(BindException::class.java) {
                DatagramSocket(null as java.net.SocketAddress?).use { socket -> socket.bind(bound) }
            }
            assertFalse(dial.isDone, "A failed dial must wait until its UDP parent has deregistered")
            val termination = parentSeen.get(10, TimeUnit.SECONDS).eventLoop().parent().shutdownGracefully(0, 5, TimeUnit.SECONDS)
            releaseUnregister.countDown()
            val failure = assertThrows(ExecutionException::class.java) { dial.get(10, TimeUnit.SECONDS) }
            assertSame(expected, failure.cause)
            termination.get(10, TimeUnit.SECONDS)
            DatagramSocket(null).use { socket -> socket.bind(bound) }
        } finally {
            releaseUnregister.countDown()
            try {
                client.close().get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
