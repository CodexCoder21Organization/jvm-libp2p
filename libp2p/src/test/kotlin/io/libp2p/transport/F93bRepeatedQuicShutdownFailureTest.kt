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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93bRepeatedQuicShutdownFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun failedUdpParentCloseStillTerminatesTheOwnedGroup(childAlsoFails: Boolean) {
        val serverKey = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(serverKey, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val accepted = CompletableFuture<Connection>()
        val parentFailure = IOException("The test UDP parent close failed after releasing its socket")
        val childFailure = IOException("The test QUIC child close failed before the UDP parent close")
        val parentCloseCount = AtomicInteger()
        var clientParent: Channel? = null
        var clientChild: Channel? = null
        var clientGroup: EventLoopGroup? = null
        var clientCloseAttempted = false
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { accepted.complete(it) }, null)
                .get(10, TimeUnit.SECONDS)
            val remoteAddress = server.listenAddresses().single()
                .withP2P(PeerId.fromPubKey(serverKey.publicKey()))
            val connection = client.dial(remoteAddress, { }, null).get(10, TimeUnit.SECONDS)
            val child = (connection as ConnectionOverNetty).nettyChannel
            clientChild = child
            val parent = child.parent()
            clientParent = parent
            val ownedGroup = parent.eventLoop().parent()
            clientGroup = ownedGroup
            val ownedThreads = ownedGroup.map { executor ->
                executor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
            }
            val childCloseFuture = child.closeFuture()
            val parentCloseFuture = parent.closeFuture()
            val serverChild = (accepted.get(10, TimeUnit.SECONDS) as ConnectionOverNetty).nettyChannel
            val serverListener = serverChild.parent()
            val independentServerGroup = serverListener.eventLoop().parent()
            assertTrue(child.isOpen, "The outbound QUIC child must be open before transport shutdown")
            assertTrue(parent.isOpen, "The client UDP parent must be open before transport shutdown")

            parent.pipeline().addFirst(
                "release-then-fail-udp-close",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        parentCloseCount.incrementAndGet()
                        ctx.close(ctx.newPromise()).addListener { promise.setFailure(parentFailure) }
                    }
                }
            )
            if (childAlsoFails) {
                child.pipeline().addFirst(
                    "release-then-fail-child-close",
                    object : ChannelOutboundHandlerAdapter() {
                        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                            ctx.close(ctx.newPromise()).addListener { promise.setFailure(childFailure) }
                        }
                    }
                )
            }
            parent.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
            child.eventLoop().submit { }.get(10, TimeUnit.SECONDS)

            val shutdown = client.close().also { clientCloseAttempted = true }
            val error = assertThrows(ExecutionException::class.java) {
                shutdown.get(10, TimeUnit.SECONDS)
            }
            assertSame(
                if (childAlsoFails) childFailure else parentFailure,
                error.cause,
                "Transport close must retain the child failure first, otherwise the UDP parent failure"
            )
            val secondError = assertThrows(ExecutionException::class.java) {
                client.close().get(10, TimeUnit.SECONDS)
            }
            assertSame(error.cause, secondError.cause, "Repeated shutdown must retain the original failed close after channel removal")
            assertEquals(1, parentCloseCount.get(), "Transport shutdown must request the owned UDP parent's close exactly once")
            assertTrue(childCloseFuture.isDone, "The QUIC child physical close future must settle before shutdown returns")
            assertTrue(childCloseFuture.isSuccess, "The QUIC child must physically close despite a reported operation failure")
            assertFalse(child.isOpen, "The QUIC child must be physically closed")
            assertFalse(child.isRegistered, "The QUIC child must be unregistered")
            assertTrue(parentCloseFuture.isDone, "The UDP parent physical close future must settle before shutdown returns")
            assertTrue(parentCloseFuture.isSuccess, "The UDP parent must physically close before its failure is reported")
            assertFalse(parent.isOpen, "The UDP parent must be physically closed")
            assertFalse(parent.isRegistered, "The UDP parent must be unregistered")
            assertTrue(parent.eventLoop().isTerminated, "The UDP parent's event loop must terminate after the failed close")
            assertTrue(ownedGroup.isTerminated, "A failed UDP parent close must not skip owned group termination")
            assertTrue(ownedGroup.terminationFuture().isSuccess, "The owned group termination future must succeed")
            ownedThreads.forEach { thread ->
                thread.join(10000)
                assertFalse(thread.isAlive, "Owned QUIC thread ${thread.name} must terminate after a UDP parent close failure")
            }
            assertFalse(independentServerGroup.isShuttingDown, "Closing the client must preserve the independent server group")
            assertFalse(independentServerGroup.isTerminated, "The independent server group must remain alive")
            assertTrue(serverListener.isOpen, "The independent server listening UDP parent must remain open")
        } finally {
            try {
                if (!clientCloseAttempted) client.close().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    if (clientGroup?.isTerminated == false) {
                        clientChild?.takeIf { it.isOpen }?.close()?.syncUninterruptibly()
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
