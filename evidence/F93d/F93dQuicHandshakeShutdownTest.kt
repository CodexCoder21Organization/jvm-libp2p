package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.Libp2pException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93dQuicHandshakeShutdownTest {
    @Test
    fun shutdownSettlesAnInFlightHandshakeAndReleasesItsBoundUdpPort() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val accepted = CompletableFuture<Connection>()
        val pendingSender = CompletableFuture<InetSocketAddress>()
        val publications = AtomicInteger()
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { accepted.complete(it) }, null).get(10, TimeUnit.SECONDS)
            val address = server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey()))
            val first = client.dial(address, { }, null).get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            val listener = (accepted.get(10, TimeUnit.SECONDS) as ConnectionOverNetty).nettyChannel.parent()
            val firstSender = first.nettyChannel.parent().localAddress()
            listener.pipeline().addFirst("hold-new-handshakes", object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, message: Any) {
                    if (message is DatagramPacket && message.sender() != firstSender) {
                        pendingSender.complete(message.sender())
                        ReferenceCountUtil.release(message)
                    } else {
                        ctx.fireChannelRead(message)
                    }
                }
            })
            listener.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            val pending = client.dial(address, { publications.incrementAndGet() }, null)
            val boundAddress = pendingSender.get(10, TimeUnit.SECONDS)
            assertFalse(pending.isDone, "The second dial must be inside its real handshake when close begins")
            client.close().get(10, TimeUnit.SECONDS)
            assertTrue(pending.isDone, "Transport close must settle the in-flight dial before returning")
            val failure = assertThrows(ExecutionException::class.java) { pending.get(10, TimeUnit.SECONDS) }
            assertTrue(failure.cause is Libp2pException, "The stopped handshake must report transport closure")
            assertEquals("Transport is closed", failure.cause!!.message)
            assertEquals(0, publications.get(), "A stopped handshake must never publish a connection")
            assertEquals(0, client.activeConnections)
            DatagramSocket(null).use { socket -> socket.bind(boundAddress) }
            listener.pipeline().remove("hold-new-handshakes")
            assertTrue(listener.isOpen, "Closing the dialer must preserve the independent server")
        } finally {
            try {
                client.close().get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
