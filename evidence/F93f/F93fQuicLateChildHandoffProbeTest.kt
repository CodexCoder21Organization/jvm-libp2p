package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.Libp2pException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrComponent
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Public ordering evidence only; the rejected local child's physical close result is not observed. */
class F93fQuicLateChildHandoffProbeTest {
    private data class Accepted(
        val sequence: Int,
        val remoteUdpAddress: InetSocketAddress,
        val channel: Channel
    )

    private data class HeldClose(
        val context: ChannelHandlerContext,
        val promise: ChannelPromise
    )

    @Test
    fun lateDialWaitsForTheExistingChildBarrierBeforeItsUdpParentCanClose() {
        val serverKey = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(serverKey, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val accepted = LinkedBlockingQueue<Accepted>()
        val acceptedSequence = AtomicInteger()
        val triggered = AtomicBoolean()
        val shutdown = CompletableFuture<CompletableFuture<Unit>>()
        val anchorCloseRequest = CompletableFuture<HeldClose>()
        val releaseGroupLoops = CountDownLatch(1)
        var anchorCloseHandlerInstalled = false

        fun awaitAccepted(expectedSequence: Int): Accepted {
            val result = accepted.poll(10, TimeUnit.SECONDS)
            assertNotNull(result, "The server must accept dial $expectedSequence")
            assertEquals(expectedSequence, result!!.sequence, "Accepted dials must remain ordered")
            return result
        }

        try {
            server.listen(
                Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"),
                { connection: Connection ->
                    val channel = (connection as ConnectionOverNetty).nettyChannel
                    // Read the peer's UDP endpoint before the accepted QUIC child closes.
                    val remoteUdpAddress = channel.remoteSocketAddress() as InetSocketAddress
                    accepted.offer(Accepted(acceptedSequence.incrementAndGet(), remoteUdpAddress, channel))
                },
                null
            ).get(10, TimeUnit.SECONDS)

            val serverAddress = server.listenAddresses().single()
                .withP2P(PeerId.fromPubKey(serverKey.publicKey()))

            val warmup = client.dial(serverAddress, { }, null).get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            awaitAccepted(1)
            val group = warmup.nettyChannel.parent().eventLoop().parent()
            warmup.close().get(10, TimeUnit.SECONDS)
            val warmupParent = warmup.nettyChannel.parent()
            assertTrue(warmupParent.closeFuture().await(10, TimeUnit.SECONDS))
            assertTrue(warmupParent.closeFuture().isSuccess)

            val anchor = client.dial(serverAddress, { }, null).get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            awaitAccepted(2)
            anchor.nettyChannel.pipeline().addFirst(
                "hold-anchor-close-result",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        anchorCloseRequest.complete(HeldClose(ctx, promise))
                    }
                }
            )
            anchorCloseHandlerInstalled = true
            anchor.nettyChannel.eventLoop().submit { }.get(10, TimeUnit.SECONDS)

            val heldLoops = group.map { loop ->
                val entered = CompletableFuture<Unit>()
                loop.execute {
                    entered.complete(Unit)
                    check(releaseGroupLoops.await(10, TimeUnit.SECONDS)) {
                        "The late-child probe must release every client event loop"
                    }
                }
                entered
            }
            heldLoops.forEach { it.get(10, TimeUnit.SECONDS) }

            val lateAddress = Multiaddr(
                object : AbstractList<MultiaddrComponent>() {
                    override val size: Int
                        get() = serverAddress.components.size

                    override fun get(index: Int): MultiaddrComponent {
                        if (group.any { it.inEventLoop() } && triggered.compareAndSet(false, true)) {
                            shutdown.complete(client.close())
                            assertFalse(
                                group.isShuttingDown,
                                "The held anchor child must keep the native group out of shutdown"
                            )
                        }
                        return serverAddress.components[index]
                    }
                }
            )
            val publications = AtomicInteger()
            val lateDial = client.dial(
                lateAddress,
                {
                    publications.incrementAndGet()
                    fail<Unit>("A dial rejected during shutdown must not publish a connection")
                },
                null
            )

            assertFalse(lateDial.isDone, "The third UDP bind must remain queued while client loops are held")
            assertFalse(triggered.get(), "The address callback must wait for the queued dial continuation")
            releaseGroupLoops.countDown()

            val closing = shutdown.get(10, TimeUnit.SECONDS)
            val heldAnchor = anchorCloseRequest.get(10, TimeUnit.SECONDS)
            assertFalse(heldAnchor.promise.isDone, "The anchor close result must remain held")
            assertTrue(anchor.nettyChannel.isOpen, "The anchor close handler must not physically close its child")
            assertFalse(group.isShuttingDown, "Native shutdown must wait for the held child close")
            assertTrue(triggered.get(), "The late address callback must start transport shutdown")

            val lateAccepted = awaitAccepted(3)
            assertTrue(
                lateAccepted.channel.closeFuture().await(10, TimeUnit.SECONDS),
                "The server-side QUIC child must observe the late connection close"
            )
            assertTrue(lateAccepted.channel.closeFuture().isSuccess)
            assertFalse(heldAnchor.promise.isDone, "The late server child must close while the anchor result is held")
            assertFalse(lateDial.isDone, "The rejected dial must wait for the UDP parent cleanup barrier")
            assertFalse(group.isShuttingDown, "The UDP parent cleanup barrier must still hold the group open")

            val remoteUdpAddress = lateAccepted.remoteUdpAddress
            assertTrue(remoteUdpAddress.port > 0, "The server must capture the client's UDP source port")
            assertThrows(SocketException::class.java, "The late dial's UDP parent must remain bound") {
                DatagramSocket(null).use { probe ->
                    probe.reuseAddress = false
                    probe.bind(InetSocketAddress(remoteUdpAddress.address, remoteUdpAddress.port))
                }
            }

            heldAnchor.context.close(heldAnchor.promise)
            closing.get(10, TimeUnit.SECONDS)
            val lateDialFailure = assertThrows(ExecutionException::class.java) {
                lateDial.get(10, TimeUnit.SECONDS)
            }
            assertTrue(lateDialFailure.cause is Libp2pException)
            assertEquals("Transport is closed", lateDialFailure.cause!!.message)
            assertEquals(0, publications.get(), "The late connection must not reach the caller's publication callback")
            assertEquals(0, client.activeConnections)
            assertTrue(group.isTerminated, "The client event-loop group must terminate after parent cleanup")
            assertTrue(group.terminationFuture().isSuccess)

            DatagramSocket(null).use { probe ->
                probe.reuseAddress = false
                probe.bind(InetSocketAddress(remoteUdpAddress.address, remoteUdpAddress.port))
            }
            assertEquals(1, server.activeListeners, "Client shutdown must preserve the server listener")
            assertEquals(serverAddress.withoutP2P(), server.listenAddresses().single())
        } finally {
            releaseGroupLoops.countDown()
            try {
                val closing = shutdown.getNow(null) ?: client.close()
                if (anchorCloseHandlerInstalled) {
                    val held = anchorCloseRequest.get(10, TimeUnit.SECONDS)
                    if (!held.promise.isDone) held.context.close(held.promise)
                }
                closing.get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
