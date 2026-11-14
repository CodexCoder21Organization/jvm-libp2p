package io.libp2p.transport

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class F93jCallbackPendingChildReleaseTest {
    @Test
    fun callbackCloseRequestKeepsTheDialPendingUntilChildAndParentRelease() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val connectionSeen = CompletableFuture<ConnectionOverNetty>()
        val closeRequest = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val dial = client.dial(
                server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey())),
                { },
                { connection ->
                    val netty = connection as ConnectionOverNetty
                    netty.nettyChannel.pipeline().addLast(object : ChannelOutboundHandlerAdapter() {
                        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                            closeRequest.complete(ctx to promise)
                        }
                    })
                    connectionSeen.complete(netty)
                    connection.close()
                }
            )
            val held = closeRequest.get(10, TimeUnit.SECONDS)
            val connection = connectionSeen.get(10, TimeUnit.SECONDS)
            val parent = connection.nettyChannel.parent()
            val address = parent.localAddress() as InetSocketAddress
            assertTrue(connection.nettyChannel.isOpen, "The physical child close must remain held")
            assertThrows(BindException::class.java) {
                DatagramSocket(null as java.net.SocketAddress?).use { socket -> socket.bind(address) }
            }
            assertFalse(dial.isDone, "A callback close request must hold dial completion even before physical child closure")
            held.first.close(held.second)
            val completed = dial.get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            assertFalse(completed.nettyChannel.isOpen)
            assertFalse(parent.isOpen)
            DatagramSocket(null as java.net.SocketAddress?).use { socket -> socket.bind(address) }
        } finally {
            closeRequest.getNow(null)?.let { held ->
                if (!held.second.isDone) held.first.close(held.second)
            }
            try {
                client.close().get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
