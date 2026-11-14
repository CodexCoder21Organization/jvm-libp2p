package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.util.concurrent.ImmediateEventExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93jQuicSameLoopReleaseTest {
    @Test
    fun sameLoopDialReleasesDoNotCompleteBeforeBothParentsRelease() {
        val serverKey = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(serverKey, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val attempts = mutableListOf<F93jSameLoopDial>()
        val continueLoop = CountDownLatch(1)

        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val remoteAddress = server.listenAddresses().single()
                .withP2P(PeerId.fromPubKey(serverKey.publicKey()))

            val first = startFailedDial(client, remoteAddress)
            attempts += first
            val firstParent = first.parent.get(10, TimeUnit.SECONDS)
            val loopIterator = firstParent.eventLoop().parent().iterator()
            var actualLoopCount = 0
            while (loopIterator.hasNext()) {
                loopIterator.next()
                actualLoopCount++
            }
            assertTrue(actualLoopCount > 0)

            repeat(actualLoopCount) {
                attempts += startFailedDial(client, remoteAddress)
            }
            attempts.forEach { it.closeRequest.get(10, TimeUnit.SECONDS) }

            val sameLoopPair = attempts.indices.asSequence().flatMap { left ->
                (left + 1 until attempts.size).asSequence().map { right -> left to right }
            }.firstOrNull { (left, right) ->
                attempts[left].parent.get(10, TimeUnit.SECONDS).eventLoop() ===
                    attempts[right].parent.get(10, TimeUnit.SECONDS).eventLoop()
            }
            assertTrue(sameLoopPair != null, "The loop-count dials must include two parents on one actual event loop")
            val firstHeld = attempts[sameLoopPair!!.first]
            val secondHeld = attempts[sameLoopPair.second]
            val releaseLoop = firstHeld.parent.get(10, TimeUnit.SECONDS).eventLoop()
            assertSame(releaseLoop, secondHeld.parent.get(10, TimeUnit.SECONDS).eventLoop())

            val otherAttempts = attempts.filter { it !== firstHeld && it !== secondHeld }
            otherAttempts.forEach { releaseNormally(it) }

            listOf(firstHeld, secondHeld).forEach { held ->
                assertFalse(held.dial.isDone)
                assertUdpPortIsStillBound(held.localAddress.get(10, TimeUnit.SECONDS))
            }

            val heldGate = CountDownLatch(1)
            val bothPhysicalCloses = CompletableFuture<CompletableFuture<Void>>()
            val releaseLoopTask = releaseLoop.submit {
                val firstClose = closeParentAndCompleteOriginalPromises(firstHeld)
                val secondClose = closeParentAndCompleteOriginalPromises(secondHeld)
                bothPhysicalCloses.complete(CompletableFuture.allOf(firstClose, secondClose))
                heldGate.countDown()
                check(continueLoop.await(10, TimeUnit.SECONDS)) {
                    "The same-loop release gate was not opened"
                }
            }

            assertTrue(heldGate.await(10, TimeUnit.SECONDS), "Both physical close requests must reach the loop gate")
            assertFalse(firstHeld.parent.get(10, TimeUnit.SECONDS).isOpen)
            assertFalse(secondHeld.parent.get(10, TimeUnit.SECONDS).isOpen)
            assertFalse(firstHeld.dial.isDone)
            assertFalse(secondHeld.dial.isDone)
            assertUdpPortIsStillBound(firstHeld.localAddress.get(10, TimeUnit.SECONDS))
            assertUdpPortIsStillBound(secondHeld.localAddress.get(10, TimeUnit.SECONDS))

            continueLoop.countDown()
            releaseLoopTask.get(10, TimeUnit.SECONDS)
            bothPhysicalCloses.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            assertDialFailedWithExpectedVisitorFailure(firstHeld)
            assertDialFailedWithExpectedVisitorFailure(secondHeld)
            assertUdpPortCanBeRebound(firstHeld.localAddress.get(10, TimeUnit.SECONDS))
            assertUdpPortCanBeRebound(secondHeld.localAddress.get(10, TimeUnit.SECONDS))
            otherAttempts.forEach {
                assertDialFailedWithExpectedVisitorFailure(it)
                assertUdpPortCanBeRebound(it.localAddress.get(10, TimeUnit.SECONDS))
            }
            attempts.filter { it !== firstHeld && it !== secondHeld }.forEach { other ->
                assertDialFailedWithExpectedVisitorFailure(other)
                assertUdpPortCanBeRebound(other.localAddress.get(10, TimeUnit.SECONDS))
            }
            attempts.forEach { assertEquals(1, it.closeRequestCount.get()) }
        } finally {
            continueLoop.countDown()
            try {
                attempts.forEach { releaseIfCaptured(it) }
            } finally {
                try {
                    client.close().get(10, TimeUnit.SECONDS)
                } finally {
                    server.close().get(10, TimeUnit.SECONDS)
                }
            }
        }
    }
}

private class F93jSameLoopDial(val expectedFailure: IllegalStateException) {
    val parent = CompletableFuture<Channel>()
    val localAddress = CompletableFuture<InetSocketAddress>()
    val closeRequest = CompletableFuture<F93jSameLoopCloseRequest>()
    val closeRequests = ConcurrentLinkedQueue<F93jSameLoopCloseRequest>()
    val closeRequestCount = AtomicInteger()
    lateinit var dial: CompletableFuture<Connection>
}

private data class F93jSameLoopCloseRequest(
    val context: ChannelHandlerContext,
    val promise: ChannelPromise
)

private fun releaseNormally(attempt: F93jSameLoopDial) {
    val request = attempt.closeRequest.get(10, TimeUnit.SECONDS)
    val completed = CompletableFuture<Unit>()
    val releaseTask = request.context.executor().submit {
        closeParentAndCompleteOriginalPromises(attempt).whenComplete { _, failure ->
            if (failure == null) completed.complete(Unit) else completed.completeExceptionally(failure)
        }
    }
    releaseTask.get(10, TimeUnit.SECONDS)
    completed.get(10, TimeUnit.SECONDS)
}

private fun closeParentAndCompleteOriginalPromises(attempt: F93jSameLoopDial): CompletableFuture<Unit> {
    val request = attempt.closeRequest.getNow(null)
        ?: throw IllegalStateException("The dial parent has not received an outbound close request")
    val completed = CompletableFuture<Unit>()
    val physicalClose = DefaultChannelPromise(request.context.channel(), ImmediateEventExecutor.INSTANCE)
    physicalClose.addListener { result ->
        if (!result.isSuccess) {
            completed.completeExceptionally(result.cause() ?: IllegalStateException("The QUIC UDP parent did not close"))
        } else {
            attempt.closeRequests.forEach { it.promise.trySuccess() }
            completed.complete(Unit)
        }
    }
    request.context.close(physicalClose)
    return completed
}

private fun releaseIfCaptured(attempt: F93jSameLoopDial) {
    if (!attempt.closeRequest.isDone || attempt.dial.isDone) return
    releaseNormally(attempt)
}

private fun startFailedDial(client: QuicTransport, remoteAddress: Multiaddr): F93jSameLoopDial {
    val attempt = F93jSameLoopDial(IllegalStateException("The same-loop test visitor rejects this dial"))
    attempt.dial = client.dial(
        remoteAddress,
        ConnectionHandler { connection ->
            val parent = (connection as ConnectionOverNetty).nettyChannel.parent()
            attempt.parent.complete(parent)
            attempt.localAddress.complete(parent.localAddress() as InetSocketAddress)
            parent.pipeline().addLast(object : ChannelOutboundHandlerAdapter() {
                override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                    val request = F93jSameLoopCloseRequest(ctx, promise)
                    attempt.closeRequestCount.incrementAndGet()
                    attempt.closeRequests.add(request)
                    attempt.closeRequest.complete(request)
                }
            })
            throw attempt.expectedFailure
        }
    )
    return attempt
}

private fun assertDialFailedWithExpectedVisitorFailure(attempt: F93jSameLoopDial) {
    val failure = assertThrows(ExecutionException::class.java) {
        attempt.dial.get(10, TimeUnit.SECONDS)
    }
    assertSame(attempt.expectedFailure, failure.cause)
}

private fun assertUdpPortIsStillBound(address: InetSocketAddress) {
    assertThrows(BindException::class.java) {
        DatagramSocket(null).use { it.bind(address) }
    }
}

private fun assertUdpPortCanBeRebound(address: InetSocketAddress) {
    DatagramSocket(null).use { it.bind(address) }
}
