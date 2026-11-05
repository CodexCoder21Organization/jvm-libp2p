package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93dQuicFailedHandshakeTest {
    @Test
    fun failedHandshakeReleasesItsBoundUdpPortWhileTheTransportRemainsUsable() {
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
            val firstPort = (first.nettyChannel.parent().localAddress() as InetSocketAddress).port
            listener.pipeline().addFirst(
                "observe-new-handshakes",
                object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(
                        ctx: ChannelHandlerContext,
                        message: Any
                    ) {
                        if (message is DatagramPacket && message.sender().port != firstPort) {
                            pendingSender.complete(message.sender())
                        }
                        ctx.fireChannelRead(message)
                    }
                }
            )
            listener.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            val wrongPeer = PeerId.fromPubKey(generateEd25519KeyPair().first.publicKey())
            val wrongAddress = server.listenAddresses().single().withP2P(wrongPeer)
            val pending = client.dial(wrongAddress, { publications.incrementAndGet() }, null)
            val boundAddress = pendingSender.get(10, TimeUnit.SECONDS)
            assertThrows(ExecutionException::class.java) { pending.get(10, TimeUnit.SECONDS) }
            assertEquals(0, publications.get(), "A failed peer handshake must never publish a connection")
            assertDoesNotThrow({
                DatagramSocket(null).use {
                        socket ->
                    socket.bind(boundAddress)
                }
            }, "A failed dial must release its bound UDP port before reporting its result")
            listener.pipeline().remove("observe-new-handshakes")
            val recovered = client.dial(address, { }, null).get(10, TimeUnit.SECONDS)
            recovered.close().get(10, TimeUnit.SECONDS)
            assertTrue(listener.isOpen, "A failed dial must preserve the independent server")
        } finally {
            try {
                client.close().get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
