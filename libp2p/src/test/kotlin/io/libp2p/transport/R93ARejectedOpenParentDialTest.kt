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
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class R93ARejectedOpenParentDialTest {
    @Test
    fun rejectedCloseLeavingParentOpenDoesNotWaitForSelectorRelease() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val addressSeen = CompletableFuture<InetSocketAddress>()
        val parentSeen = CompletableFuture<io.netty.channel.Channel>()
        val closeFailure = java.util.concurrent.RejectedExecutionException("The UDP close request is rejected while its parent remains open")
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
                            promise.tryFailure(closeFailure)
                        }
                    })
                    throw expected
                }
            )
            val bound = addressSeen.get(10, TimeUnit.SECONDS)
            val failure = assertThrows(ExecutionException::class.java) { dial.get(10, TimeUnit.SECONDS) }
            assertSame(expected, failure.cause)
            assertSame(closeFailure, expected.suppressed.single())
            assertTrue(parentSeen.get(10, TimeUnit.SECONDS).isOpen, "A rejected close must still leave its parent open here")
            assertThrows(BindException::class.java) {
                DatagramSocket(null as java.net.SocketAddress?).use { socket -> socket.bind(bound) }
            }
        } finally {
            try {
                parentSeen.get(10, TimeUnit.SECONDS).eventLoop().parent()
                    .shutdownGracefully(0, 10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
                val closing = assertThrows(ExecutionException::class.java) { client.close().get(10, TimeUnit.SECONDS) }
                assertSame(closeFailure, closing.cause)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
