package io.libp2p.transport

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.concurrent.SingleThreadEventExecutor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class F93fQuicFullQueueTest {
    @Test
    fun shutdownClosesItsRealChannelsWhenTheNativeQueueIsFull() {
        // The queue capacity is fixed at Netty initialization, so this scenario owns a fresh JVM.
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-ea", "-cp", System.getProperty("java.class.path"),
            "io.libp2p.transport.F93fQuicFullQueueProbe"
        ).inheritIO().start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "The full-queue shutdown probe must finish")
            assertEquals(0, process.exitValue(), "The full-queue shutdown probe must pass every assertion")
        } finally {
            assertTrue(process.destroyForcibly().waitFor(5, TimeUnit.SECONDS), "The owned full-queue probe JVM must exit during cleanup")
        }
    }
}

object F93fQuicFullQueueProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        // Shrink the real dependency's queue before any Netty class initializes; no machine load is needed.
        System.setProperty("io.netty.eventLoop.maxPendingTasks", "2048")
        System.setProperty("io.netty.eventLoopThreads", "4")
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val release = CountDownLatch(1)
        var closeStarted = false
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val address = server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey()))
            val connection = client.dial(address, { }, null).get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            val child = connection.nettyChannel
            val parent = child.parent()
            val loop = parent.eventLoop() as SingleThreadEventExecutor
            val group = loop.parent()
            val threads = group.map { executor ->
                executor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
            }
            val unregistered = listOf(child, parent).map { channel ->
                val observed = CompletableFuture<Unit>()
                channel.eventLoop().submit {
                    channel.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelUnregistered(ctx: ChannelHandlerContext) {
                            observed.complete(Unit)
                            ctx.fireChannelUnregistered()
                        }
                    })
                }.get(10, TimeUnit.SECONDS)
                observed
            }
            val entered = CompletableFuture<Unit>()
            loop.execute {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "The full-queue fixture must release the loop" }
            }
            entered.get(10, TimeUnit.SECONDS)
            assertThrows(RejectedExecutionException::class.java) {
                repeat(2049) { loop.execute { } }
            }
            assertEquals(2048, loop.pendingTasks(), "The actual native queue must be full before shutdown")
            assertFalse(loop.isShutdown, "Queue fullness must be the rejection condition, before shutdown")
            val closing = client.close().also { closeStarted = true }
            release.countDown()
            val failure = closing.handle { _, error -> error }.get(10, TimeUnit.SECONDS)
            assertTrue(generateSequence(failure) { it.cause }.any { it is RejectedExecutionException }, "Shutdown must report its native queue rejection")
            assertTrue(child.closeFuture().isDone, "The rejected close must settle the real child close future")
            assertTrue(parent.closeFuture().isDone, "The rejected close must settle the real UDP parent close future")
            assertFalse(child.isOpen, "The rejected close must physically close its child")
            assertFalse(parent.isOpen, "The rejected close must physically close its UDP parent")
            assertTrue(child.closeFuture().isSuccess)
            assertTrue(parent.closeFuture().isSuccess)
            unregistered.forEach { it.get(10, TimeUnit.SECONDS) }
            assertFalse(child.isRegistered)
            assertFalse(parent.isRegistered)
            assertTrue(group.isTerminated, "The owned group must terminate after physical cleanup")
            assertTrue(group.terminationFuture().isSuccess)
            threads.forEach { thread ->
                thread.join(10000)
                assertFalse(thread.isAlive, "Every owned worker thread must exit after physical cleanup")
            }
            assertEquals(1, server.activeListeners, "The independent server must remain usable")
            val independentClient = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
            try {
                independentClient.dial(address, { }, null).get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
            } finally {
                independentClient.close().get(10, TimeUnit.SECONDS)
            }
        } finally {
            release.countDown()
            try {
                if (!closeStarted) client.close().get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
