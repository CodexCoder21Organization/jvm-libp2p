package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.EventLoopGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class QuicTransportFailedConnectionSetTest {
    @Test
    fun oneOutboundCloseFailureStillClosesEveryConnectionAndUdpParent() {
        val serverKey = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(serverKey, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val accepted = LinkedBlockingQueue<Connection>()
        val clientChannels = mutableListOf<Channel>()
        val serverChannels = mutableListOf<Channel>()
        var clientGroup: EventLoopGroup? = null
        var serverGroup: EventLoopGroup? = null
        var clientCloseAttempted = false
        var serverCloseAttempted = false
        val failure = IOException("The test QUIC connection close failed after releasing its socket")
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { accepted.offer(it) }, null)
                .get(10, TimeUnit.SECONDS)
            val remoteAddress = server.listenAddresses().single()
                .withP2P(PeerId.fromPubKey(serverKey.publicKey()))
            repeat(4) { index ->
                val connection = client.dial(remoteAddress, { }, null).get(10, TimeUnit.SECONDS)
                clientChannels += (connection as ConnectionOverNetty).nettyChannel
                val inbound = accepted.poll(10, TimeUnit.SECONDS)
                assertTrue(inbound != null, "Direct client dial $index must produce an accepted QUIC connection")
                serverChannels += (inbound as ConnectionOverNetty).nettyChannel
            }

            assertEquals(4, clientChannels.size, "All four direct dials must be retained by the client transport")
            assertTrue(clientChannels.all { it.isOpen }, "Every outbound QUIC connection must be open before close")
            val clientParents = clientChannels.map { it.parent() }
            val identityParents = Collections.newSetFromMap(IdentityHashMap<Channel, Boolean>())
            identityParents.addAll(clientParents)
            assertEquals(4, identityParents.size, "Each outbound QUIC connection must own a distinct UDP parent")
            val serverParents = serverChannels.map { it.parent() }.distinct()
            assertEquals(1, serverParents.size, "All accepted server connections must use the one listening UDP parent")

            val ownedGroup = clientChannels.first().eventLoop().parent()
            clientGroup = ownedGroup
            val listeningGroup = serverChannels.first().eventLoop().parent()
            serverGroup = listeningGroup
            val clientThreads = ownedGroup.map { executor ->
                executor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
            }
            val clientCloseFutures = clientChannels.map { it.closeFuture() }
            val parentCloseFutures = identityParents.map { it.closeFuture() }
            val firstClientChannel = clientChannels.first()
            firstClientChannel.pipeline().addFirst(
                "release-then-fail-close",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        ctx.close(ctx.newPromise()).addListener { promise.setFailure(failure) }
                    }
                }
            )
            firstClientChannel.eventLoop().submit { }.get(10, TimeUnit.SECONDS)

            val close = client.close().also { clientCloseAttempted = true }
            val error = assertThrows(ExecutionException::class.java) {
                close.get(10, TimeUnit.SECONDS)
            }
            assertSame(failure, error.cause, "The client transport must report the injected failure object")
            assertTrue(ownedGroup.isTerminated, "Failed close must still terminate the owned QUIC worker group")

            clientCloseFutures.forEach { future ->
                assertTrue(future.await(10, TimeUnit.SECONDS), "Every outbound QUIC close future must settle")
                assertTrue(future.isSuccess, "Every outbound QUIC channel must be physically closed")
            }
            parentCloseFutures.forEach { future ->
                assertTrue(future.await(10, TimeUnit.SECONDS), "Every client UDP parent close future must settle")
                assertTrue(future.isSuccess, "Every client UDP parent must be physically closed")
            }
            assertTrue(clientChannels.all { !it.isOpen }, "All four outbound QUIC channels must be closed")
            assertTrue(clientParents.all { !it.isOpen }, "All distinct client UDP parents must be closed")

            assertTrue(ownedGroup.terminationFuture().await(10, TimeUnit.SECONDS), "The client group must terminate")
            assertTrue(ownedGroup.isTerminated, "The client group must be terminated")
            assertTrue(ownedGroup.terminationFuture().isSuccess, "The client termination future must succeed")
            clientThreads.forEach { thread ->
                thread.join(10000)
                assertFalse(thread.isAlive, "Client QUIC worker ${thread.name} must terminate")
            }
            assertFalse(listeningGroup.isShuttingDown, "Closing the client must preserve the independent server group")
            assertFalse(listeningGroup.isTerminated, "The server group must remain alive")
            assertTrue(serverParents.single().isOpen, "The server listening UDP parent must remain open")
        } finally {
            try {
                if (!clientCloseAttempted) client.close().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    if (clientGroup?.isTerminated == false) {
                        clientChannels.filter { it.isOpen }.forEach { it.close().syncUninterruptibly() }
                        clientGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
                    }
                } finally {
                    try {
                        if (!serverCloseAttempted) server.close().also { serverCloseAttempted = true }.get(10, TimeUnit.SECONDS)
                    } finally {
                        if (serverGroup?.isTerminated == false) {
                            serverChannels.filter { it.isOpen }.forEach { it.close().syncUninterruptibly() }
                            serverChannels.mapNotNull { it.parent() }.distinct().filter { it.isOpen }
                                .forEach { it.close().syncUninterruptibly() }
                            serverGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
                        }
                    }
                }
            }
        }
    }
}
