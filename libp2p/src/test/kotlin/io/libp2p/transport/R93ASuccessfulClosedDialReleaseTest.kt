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
import java.util.concurrent.TimeUnit

class R93ASuccessfulClosedDialReleaseTest {
    @Test
    fun successfulClosedDialWaitsForItsUdpPortRelease() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val addressSeen = CompletableFuture<InetSocketAddress>()
        val unregisterEntered = CompletableFuture<Unit>()
        val releaseUnregister = CountDownLatch(1)
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val dial = client.dial(
                server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey())),
                { },
                { connection ->
                    val parent = (connection as ConnectionOverNetty).nettyChannel.parent()
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
                    connection.close()
                }
            )
            val bound = addressSeen.get(10, TimeUnit.SECONDS)
            unregisterEntered.get(10, TimeUnit.SECONDS)
            assertThrows(BindException::class.java) {
                DatagramSocket(null as java.net.SocketAddress?).use { socket -> socket.bind(bound) }
            }
            assertFalse(dial.isCompletedExceptionally, "The normally returning visitor must retain its successful dial result")
            assertFalse(dial.isDone, "A dial returning an already closed connection must wait for UDP release")
            releaseUnregister.countDown()
            dial.get(10, TimeUnit.SECONDS)
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
