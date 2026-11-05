package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class F93cQuicRejectedLoopTest {
    @Test
    fun releasedUdpParentKeepsItsSuccessfulResultWhenItsLoopLaterRejectsDispatch() {
        val serverKey = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(serverKey, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val accepted = CompletableFuture<Connection>()
        var clientParent: Channel? = null
        var clientGroup: EventLoopGroup? = null
        var clientCloseAttempted = false
        val releaseLoop = CountDownLatch(1)
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { accepted.complete(it) }, null)
                .get(10, TimeUnit.SECONDS)
            val remoteAddress =
                server.listenAddresses().single()
                    .withP2P(PeerId.fromPubKey(serverKey.publicKey()))
            val connection = client.dial(remoteAddress, { }, null).get(10, TimeUnit.SECONDS)
            val child = (connection as ConnectionOverNetty).nettyChannel
            val parent = child.parent()
            clientParent = parent
            val parentCloseFuture = parent.closeFuture()
            val ownedGroup = parent.eventLoop().parent()
            clientGroup = ownedGroup
            val ownedThreads =
                ownedGroup.map { executor ->
                    executor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
                }
            val serverChild = (accepted.get(10, TimeUnit.SECONDS) as ConnectionOverNetty).nettyChannel
            val serverListener = serverChild.parent()
            val independentServerGroup = serverListener.eventLoop().parent()
            assertTrue(child.isOpen, "The outbound QUIC child must be open before its ordinary close")
            assertTrue(parent.isOpen, "The dial must have an open UDP parent before its child closes")

            connection.close().get(10, TimeUnit.SECONDS)
            assertTrue(child.closeFuture().isDone, "The ordinary connection close must settle its physical child future")
            assertTrue(child.closeFuture().isSuccess, "The ordinary connection close must physically close its child")
            assertFalse(child.isOpen, "The outbound child must be closed before transport shutdown starts")
            assertEquals(0, client.activeConnections, "The closed child must no longer be an active transport connection")
            assertTrue(
                parentCloseFuture.await(10, TimeUnit.SECONDS),
                "An ordinary child close must release its UDP parent before native shutdown"
            )
            assertTrue(parentCloseFuture.isSuccess)
            assertFalse(parent.isOpen)

            val enteredLoop = CompletableFuture<Unit>()
            parent.eventLoop().execute {
                enteredLoop.complete(Unit)
                assertTrue(releaseLoop.await(10, TimeUnit.SECONDS), "The test must release the owned loop")
            }
            enteredLoop.get(10, TimeUnit.SECONDS)
            @Suppress("DEPRECATION")
            ownedGroup.shutdown()
            assertTrue(parent.eventLoop().isShutdown, "The native loop must reject new dispatch")
            assertFalse(parent.eventLoop().isTerminated, "The native loop must still be running the held task")
            val close = client.close().also { clientCloseAttempted = true }
            releaseLoop.countDown()
            val reportedFailure = close.handle { _, failure -> failure }.get(10, TimeUnit.SECONDS)
            assertNull(reportedFailure, "An already-released parent must not report a close rejection that never occurred")
            val repeatedFailure = client.close().handle { _, failure -> failure }.get(10, TimeUnit.SECONDS)
            assertNull(repeatedFailure, "Repeated shutdown must preserve the original successful release")

            assertTrue(
                parentCloseFuture.isDone,
                "Transport close must settle the UDP parent even after its child left the live connection set"
            )
            assertTrue(parentCloseFuture.isSuccess, "The separately owned UDP parent must close successfully")
            assertFalse(parent.isOpen, "The UDP parent must be physically closed before transport close returns")
            assertTrue(parent.eventLoop().isTerminated, "The UDP parent's event loop must terminate")
            assertTrue(ownedGroup.isTerminated, "The client's owned QUIC group must terminate")
            assertTrue(ownedGroup.terminationFuture().isSuccess, "The client's owned group must report successful termination")
            ownedThreads.forEach { thread ->
                thread.join(10000)
                assertFalse(thread.isAlive, "Owned QUIC thread ${thread.name} must terminate after the child was already closed")
            }
            assertFalse(independentServerGroup.isShuttingDown, "Closing the client must preserve the independent server group")
            assertFalse(independentServerGroup.isTerminated, "The independent server group must remain alive")
            assertTrue(serverListener.isOpen, "The independent server listening UDP parent must remain open")
        } finally {
            releaseLoop.countDown()
            try {
                if (!clientCloseAttempted) client.close().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    if (clientGroup?.isTerminated == false) {
                        clientParent?.takeIf { it.isOpen }?.close()?.syncUninterruptibly()
                        clientGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
                    }
                } finally {
                    server.close().get(10, TimeUnit.SECONDS)
                }
            }
        }
    }
}
