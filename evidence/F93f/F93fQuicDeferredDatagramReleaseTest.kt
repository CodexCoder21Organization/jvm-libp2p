package io.libp2p.transport

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class F93fQuicDeferredDatagramReleaseTest {
    @Test
    fun failedDialDoesNotReportBeforeItsUdpPortIsPhysicallyReleased() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val addressSeen = CompletableFuture<InetSocketAddress>()
        val unregisterEntered = CompletableFuture<Unit>()
        val releaseUnregister = CountDownLatch(1)
        val expected = IllegalStateException("The public dial visitor rejects this connection")
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val dial = client.dial(
                server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey())),
                { fail<Unit>("A rejected dial must not publish a connection") },
                { connection ->
                    val parent = (connection as ConnectionOverNetty).nettyChannel.parent()
                    addressSeen.complete(parent.localAddress() as InetSocketAddress)
                    parent.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelUnregistered(ctx: ChannelHandlerContext) {
                            unregisterEntered.complete(Unit)
                            check(releaseUnregister.await(10, TimeUnit.SECONDS)) { "The fixture must release UDP deregistration" }
                            ctx.fireChannelUnregistered()
                        }
                    })
                    throw expected
                }
            )
            val bound = addressSeen.get(10, TimeUnit.SECONDS)
            val failure = assertThrows(ExecutionException::class.java) { dial.get(10, TimeUnit.SECONDS) }
            assertSame(expected, failure.cause)
            unregisterEntered.get(10, TimeUnit.SECONDS)
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
