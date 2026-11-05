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
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93cQuicMultipleCloseFailuresTest {
    @Test
    fun shutdownRetainsEveryChildAndUdpParentCloseFailure() {
        val serverKey = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(serverKey, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val accepted = LinkedBlockingQueue<Connection>()
        val clientChannels = mutableListOf<Channel>()
        val serverChannels = mutableListOf<Channel>()
        val childFailures =
            List(4) { index ->
                IOException("The test QUIC child close $index failed after its socket closed")
            }
        val parentFailures =
            List(4) { index ->
                IOException("The test UDP parent close $index failed after its socket closed")
            }
        val childCloseAttempts = List(4) { AtomicInteger() }
        val parentCloseAttempts = List(4) { AtomicInteger() }
        var clientGroup: EventLoopGroup? = null
        var serverGroup: EventLoopGroup? = null
        var clientCloseAttempted = false

        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { accepted.offer(it) }, null)
                .get(10, TimeUnit.SECONDS)
            val remoteAddress =
                server.listenAddresses().single()
                    .withP2P(PeerId.fromPubKey(serverKey.publicKey()))

            repeat(4) { index ->
                val connection = client.dial(remoteAddress, { }, null).get(10, TimeUnit.SECONDS)
                val clientChannel = (connection as ConnectionOverNetty).nettyChannel
                clientChannels += clientChannel
                clientGroup = clientChannel.eventLoop().parent()

                val inbound = accepted.poll(10, TimeUnit.SECONDS)
                assertTrue(inbound != null, "Direct client dial $index must produce an accepted QUIC connection")
                val serverChannel = (inbound as ConnectionOverNetty).nettyChannel
                serverChannels += serverChannel
                serverGroup = serverChannel.eventLoop().parent()
            }

            assertEquals(4, clientChannels.size, "All four direct dials must be retained by the client transport")
            assertTrue(clientChannels.all { it.isOpen }, "Every outbound QUIC child must be open before close")
            val clientParents =
                clientChannels.map { channel ->
                    requireNotNull(channel.parent()) { "Every outbound QUIC child must have its UDP parent" }
                }
            val distinctClientParents = Collections.newSetFromMap(IdentityHashMap<Channel, Boolean>())
            distinctClientParents.addAll(clientParents)
            assertEquals(4, distinctClientParents.size, "Each outbound QUIC child must own a distinct UDP parent")
            assertTrue(clientParents.all { it.isOpen }, "Every client UDP parent must be open before close")

            assertEquals(4, serverChannels.size, "The server must accept all four direct client dials")
            val serverParents =
                serverChannels.map { channel ->
                    requireNotNull(channel.parent()) { "Every accepted QUIC child must have the listening UDP parent" }
                }
            val distinctServerParents = Collections.newSetFromMap(IdentityHashMap<Channel, Boolean>())
            distinctServerParents.addAll(serverParents)
            assertEquals(1, distinctServerParents.size, "All accepted server connections must use one listening UDP parent")
            val serverListener = serverParents.first()
            val ownedClientGroup = requireNotNull(clientGroup)
            val independentServerGroup = requireNotNull(serverGroup)
            val childCloseFutures = clientChannels.map { it.closeFuture() }
            val parentCloseFutures = clientParents.map { it.closeFuture() }

            fun installCloseFailure(
                channel: Channel,
                handlerName: String,
                failure: IOException,
                attempts: AtomicInteger
            ) {
                channel.pipeline().addFirst(
                    handlerName,
                    object : ChannelOutboundHandlerAdapter() {
                        override fun close(
                            ctx: ChannelHandlerContext,
                            promise: ChannelPromise
                        ) {
                            attempts.incrementAndGet()
                            ctx.close(ctx.newPromise()).addListener { promise.setFailure(failure) }
                        }
                    }
                )
            }

            clientChannels.forEachIndexed { index, channel ->
                installCloseFailure(channel, "fail-child-close-$index", childFailures[index], childCloseAttempts[index])
            }
            clientParents.forEachIndexed { index, channel ->
                installCloseFailure(channel, "fail-parent-close-$index", parentFailures[index], parentCloseAttempts[index])
            }
            (clientChannels.map { it.eventLoop() } + clientParents.map { it.eventLoop() })
                .distinct()
                .forEach { eventLoop -> eventLoop.submit { }.get(10, TimeUnit.SECONDS) }

            val shutdown = client.close().also { clientCloseAttempted = true }
            val repeatedShutdown = client.close()
            val shutdownError =
                assertThrows(ExecutionException::class.java) {
                    shutdown.get(10, TimeUnit.SECONDS)
                }
            val repeatedShutdownError =
                assertThrows(ExecutionException::class.java) {
                    repeatedShutdown.get(10, TimeUnit.SECONDS)
                }
            val primaryFailure = requireNotNull(shutdownError.cause) { "Transport shutdown must report its close failure" }
            assertSame(
                childFailures.first(),
                primaryFailure,
                "The first outbound child close failure must remain primary"
            )
            assertSame(
                childFailures.first(),
                repeatedShutdownError.cause,
                "Repeated transport shutdown must report the same original primary failure"
            )

            val expectedFailures = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            expectedFailures.addAll(childFailures)
            expectedFailures.addAll(parentFailures)
            assertEquals(8, expectedFailures.size, "Every child and UDP parent must have a distinct IOException object")

            // Walk both cause and suppressed links by identity; suppressed ordering is not part of this contract.
            val reachableFailures = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val pendingFailures = ArrayDeque<Throwable>()
            pendingFailures.addLast(primaryFailure)
            while (pendingFailures.isNotEmpty()) {
                val failure = pendingFailures.removeFirst()
                if (reachableFailures.add(failure)) {
                    failure.cause?.let { pendingFailures.addLast(it) }
                    failure.suppressed.forEach { pendingFailures.addLast(it) }
                }
            }
            expectedFailures.forEach { failure ->
                assertTrue(
                    reachableFailures.contains(failure),
                    "Transport shutdown must retain the exact failure object: ${failure.message}"
                )
            }

            childCloseAttempts.forEachIndexed { index, attempts ->
                assertEquals(1, attempts.get(), "Outbound child $index must receive exactly one close request")
            }
            parentCloseAttempts.forEachIndexed { index, attempts ->
                assertEquals(1, attempts.get(), "UDP parent $index must receive exactly one close request")
            }
            childCloseFutures.forEach { future ->
                assertTrue(future.isDone, "Every outbound QUIC child close future must settle before shutdown returns")
                assertTrue(future.isSuccess, "Every outbound QUIC child socket must physically close")
            }
            parentCloseFutures.forEach { future ->
                assertTrue(future.isDone, "Every client UDP parent close future must settle before shutdown returns")
                assertTrue(future.isSuccess, "Every client UDP parent socket must physically close")
            }
            assertTrue(clientChannels.all { !it.isOpen && !it.isRegistered }, "Every outbound QUIC child must be closed and unregistered")
            assertTrue(clientParents.all { !it.isOpen && !it.isRegistered }, "Every client UDP parent must be closed and unregistered")

            assertTrue(ownedClientGroup.isTerminated, "The client QUIC worker group must terminate after shutdown")
            assertTrue(ownedClientGroup.terminationFuture().isDone, "The client worker termination future must settle")
            assertTrue(ownedClientGroup.terminationFuture().isSuccess, "The client worker group must terminate successfully")
            assertFalse(independentServerGroup.isShuttingDown, "Closing the client must preserve the independent server group")
            assertFalse(independentServerGroup.isTerminated, "The independent server group must remain alive")
            assertTrue(serverListener.isOpen, "The independent server listening UDP parent must remain open")
            assertEquals(1, server.activeListeners, "The independent server must still have its listener")
        } finally {
            try {
                if (!clientCloseAttempted) client.close().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    if (clientGroup?.isTerminated == false) {
                        clientChannels.filter { it.isOpen }.forEach { it.close().syncUninterruptibly() }
                        clientChannels.mapNotNull { it.parent() }.distinct().filter { it.isOpen }
                            .forEach { it.close().syncUninterruptibly() }
                        clientGroup!!.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
                    }
                } finally {
                    try {
                        server.close().get(10, TimeUnit.SECONDS)
                    } finally {
                        if (serverGroup?.isTerminated == false) {
                            serverChannels.filter { it.isOpen }.forEach { it.close().syncUninterruptibly() }
                            serverChannels.mapNotNull { it.parent() }.distinct().filter { it.isOpen }
                                .forEach { it.close().syncUninterruptibly() }
                            serverGroup!!.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
                        }
                    }
                }
            }
        }
    }
}
