package io.libp2p.transport

import com.sun.management.UnixOperatingSystemMXBean
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93jQuicFailedDialResourceTest {
    @Test
    fun sequentialFailedDialsReleasePhysicalResourcesAndKeepTheTransportUsable() {
        data class DialResources(
            val child: Channel,
            val parent: Channel,
            val parentAddress: InetSocketAddress,
        )

        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val closeFailures = mutableListOf<Throwable>()
        val parentCloseRequestCounts = mutableListOf<AtomicInteger>()
        var clientCloseAttempted = false

        fun assertPhysicalClosure(resources: DialResources, description: String) {
            assertTrue(
                resources.child.closeFuture().await(10, TimeUnit.SECONDS),
                "$description child channel must finish closing",
            )
            assertTrue(resources.child.closeFuture().isSuccess, "$description child channel must close successfully")
            assertFalse(resources.child.isOpen, "$description child channel must be physically closed")

            assertTrue(
                resources.parent.closeFuture().await(10, TimeUnit.SECONDS),
                "$description UDP parent must finish closing",
            )
            assertTrue(resources.parent.closeFuture().isSuccess, "$description UDP parent must physically close")
            assertFalse(resources.parent.isOpen, "$description UDP parent must be physically closed")
            assertFalse(resources.parent.isRegistered, "$description UDP parent must be deregistered")
        }

        fun assertUdpPortCanBeRebound(address: InetSocketAddress, description: String) {
            assertNotEquals(0, address.port, "$description must identify the actual allocated UDP port")
            DatagramSocket(null as SocketAddress?).use { socket -> socket.bind(address) }
        }

        fun failedDialCycle(label: String) {
            val expectedVisitorFailure = IllegalStateException("The public dial visitor rejects connection $label")
            val closeFailure = IllegalStateException("The UDP parent close request fails after physical close $label")
            val closeRequests = AtomicInteger()
            val resourcesObserved = CompletableFuture<DialResources>()
            val closeListenerEntered = CountDownLatch(1)
            val releaseCloseListener = CountDownLatch(1)

            val dial = client.dial(
                server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey())),
                { fail<Unit>("A rejected dial must not publish a connection") },
                { connection ->
                    val child = (connection as ConnectionOverNetty).nettyChannel
                    val parent = child.parent()
                    val parentAddress = parent.localAddress() as InetSocketAddress
                    parent.pipeline().addLast(
                        object : ChannelOutboundHandlerAdapter() {
                            override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                                closeRequests.incrementAndGet()
                                val physicalClose = DefaultChannelPromise(parent, ImmediateEventExecutor.INSTANCE)
                                physicalClose.addListener { result ->
                                    check(result.isSuccess) { "The fixture must physically close its UDP parent" }
                                    check(ctx.executor().inEventLoop()) {
                                        "The failed-close completion listener must hold its owning event loop"
                                    }
                                    promise.tryFailure(closeFailure)
                                    closeListenerEntered.countDown()
                                    check(releaseCloseListener.await(10, TimeUnit.SECONDS)) {
                                        "The failed-close completion gate was not opened"
                                    }
                                }
                                ctx.executor().execute { ctx.close(physicalClose) }
                            }
                        }
                    )
                    closeFailures.add(closeFailure)
                    parentCloseRequestCounts.add(closeRequests)
                    resourcesObserved.complete(DialResources(child, parent, parentAddress))
                    throw expectedVisitorFailure
                },
            )

            val resources = resourcesObserved.get(10, TimeUnit.SECONDS)
            try {
                assertTrue(
                    closeListenerEntered.await(10, TimeUnit.SECONDS),
                    "$label failed-close completion must reach its owner-loop gate",
                )
                assertFalse(dial.isDone, "$label dial must remain pending while its owner loop is held")
                assertThrows(BindException::class.java) {
                    DatagramSocket(null as SocketAddress?).use { socket -> socket.bind(resources.parentAddress) }
                }
            } finally {
                releaseCloseListener.countDown()
            }

            val dialFailure = assertThrows(ExecutionException::class.java) {
                dial.get(10, TimeUnit.SECONDS)
            }
            assertSame(expectedVisitorFailure, dialFailure.cause, "$label must preserve the original visitor failure")
            assertEquals(1, expectedVisitorFailure.suppressed.size, "$label must report one secondary parent-close failure")
            assertSame(closeFailure, expectedVisitorFailure.suppressed.single(), "$label must preserve the parent-close failure identity")

            assertPhysicalClosure(resources, label)
            assertEquals(1, closeRequests.get(), "$label must issue exactly one parent close request")
            assertUdpPortCanBeRebound(resources.parentAddress, label)
        }

        fun successfulDialProbe(batch: Int) {
            val connection = client.dial(
                server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey())),
                { connection -> connection.close() },
                null,
            ).get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            val child = connection.nettyChannel
            val parent = child.parent()
            val parentAddress = parent.localAddress() as InetSocketAddress
            val resources = DialResources(child, parent, parentAddress)

            connection.close().get(10, TimeUnit.SECONDS)
            assertPhysicalClosure(resources, "successful probe after batch $batch")
            assertUdpPortCanBeRebound(parentAddress, "successful probe after batch $batch")
        }

        fun throwableGraph(root: Throwable): Set<Throwable> {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            fun visit(failure: Throwable?) {
                if (failure != null && seen.add(failure)) {
                    visit(failure.cause)
                    failure.suppressed.forEach(::visit)
                }
            }
            visit(root)
            return seen
        }

        fun assertShutdownReportsExactly(expected: List<Throwable>, shutdownFailure: Throwable) {
            val graph = throwableGraph(shutdownFailure)
            val reportedExpectedFailures = graph.filter { reported -> expected.any { it === reported } }
            assertEquals(
                expected.size,
                reportedExpectedFailures.size,
                "Transport shutdown must retain every original parent-close failure identity",
            )
            expected.forEach { original ->
                assertTrue(
                    reportedExpectedFailures.any { it === original },
                    "Transport shutdown must retain original parent-close failure identity: ${original.message}",
                )
            }
        }

        fun closeClientAndAssertFailures(expected: List<Throwable>) {
            clientCloseAttempted = true
            val shutdownFailure = try {
                client.close().get(10, TimeUnit.SECONDS)
                null
            } catch (failure: ExecutionException) {
                failure
            }
            if (expected.isEmpty()) {
                assertNull(shutdownFailure, "Client shutdown without failed dial parents must succeed")
            } else {
                val failure = shutdownFailure
                    ?: fail<ExecutionException>("Client shutdown must report its original UDP parent-close failures")
                assertShutdownReportsExactly(expected, failure)
            }
            assertTrue(
                parentCloseRequestCounts.all { it.get() == 1 },
                "Transport shutdown must not issue another physical close request for a failed dial parent",
            )
        }

        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)

            // The next operation checks the warmed process's descriptor count, so first exercise
            // the same failed-dial cleanup path and return its physical resources to baseline.
            failedDialCycle("warmup")
            val osBean = ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean
            val warmedDescriptorBaseline = osBean.openFileDescriptorCount

            repeat(128) { iteration ->
                val cycle = iteration + 1
                failedDialCycle("measured-$cycle")

                if (cycle % 32 == 0) {
                    assertEquals(
                        warmedDescriptorBaseline,
                        osBean.openFileDescriptorCount,
                        "Failed dial batch ending at $cycle must return to the warmed descriptor baseline",
                    )

                    val batch = cycle / 32
                    successfulDialProbe(batch)
                    assertEquals(
                        warmedDescriptorBaseline,
                        osBean.openFileDescriptorCount,
                        "Successful usability probe after batch $batch must return to the warmed descriptor baseline",
                    )
                }
            }

            assertEquals(129, closeFailures.size, "One warmup and 128 measured failed dials must run")
            assertEquals(129, parentCloseRequestCounts.size, "Every failed dial must install one close counter")
            closeClientAndAssertFailures(closeFailures)
        } finally {
            try {
                if (!clientCloseAttempted) {
                    closeClientAndAssertFailures(closeFailures)
                }
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
