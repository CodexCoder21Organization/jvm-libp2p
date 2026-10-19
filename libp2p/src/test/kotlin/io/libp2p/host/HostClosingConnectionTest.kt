package io.libp2p.host

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HostClosingConnectionTest {
    @Test
    fun `explicit stream open on a closing connection fails before creating a child`() {
        val hosts = mutableListOf<Host>()
        var client: Host? = null
        var closeGate: ExplicitParentCloseGate? = null
        var closeRequested: CompletableFuture<Unit>? = null
        var streamVisitor: ChannelVisitor<Stream>? = null
        var primaryFailure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()

        try {
            val clientHost = startExplicitCloseTestHost(hosts)
            client = clientHost
            val server = startExplicitCloseTestHost(hosts)
            val serverAddress = server.listenAddresses().single()
            val protocols = Ping().protocolDescriptor.announceProtocols

            val first = clientHost.network.connect(server.peerId, serverAddress).get(20, TimeUnit.SECONDS)
            val initialPing = clientHost.newStream<PingController>(protocols, first)
            val initialStream = initialPing.stream.get(15, TimeUnit.SECONDS)
            val initialController = initialPing.controller.get(15, TimeUnit.SECONDS)
            assertTrue(initialController.ping().get(15, TimeUnit.SECONDS) >= 0L)
            assertSame(first, initialStream.connection, "The initial Ping must use the captured connection")

            val nettyChannel = (first as ConnectionOverNetty).nettyChannel
            val gate = ExplicitParentCloseGate()
            closeGate = gate
            nettyChannel.eventLoop().submit {
                nettyChannel.pipeline().addFirst("holdExplicitParentPhysicalClose", gate)
            }.get(10, TimeUnit.SECONDS)

            val firstClose = first.close()
            closeRequested = firstClose
            gate.intercepted.get(10, TimeUnit.SECONDS)
            assertEquals(1, gate.closeCalls.get(), "The first public close must reach the physical close gate once")
            assertFalse(first.closeFuture().isDone, "The gate must hold the physical connection close")
            assertFalse(firstClose.isDone, "Connection.close must remain pending while the physical close is held")

            val outboundChildrenOnClosingParent = AtomicInteger()
            val visitor = ChannelVisitor<Stream> { stream ->
                if (stream.isInitiator && stream.connection === first) {
                    outboundChildrenOnClosingParent.incrementAndGet()
                }
            }
            streamVisitor = visitor
            clientHost.addStreamVisitor(visitor)
            try {
                val rejected = clientHost.newStream<PingController>(protocols, first)
                val expectedMessage =
                    "Cannot open a stream on connection ${first.remoteAddress()}: the connection is closing or closed."
                assertExplicitParentClosingFailure(rejected.stream, expectedMessage, "stream")
                assertExplicitParentClosingFailure(rejected.controller, expectedMessage, "controller")
                assertEquals(
                    0,
                    outboundChildrenOnClosingParent.get(),
                    "Rejecting an explicit-parent open must not create an outbound child stream",
                )
            } finally {
                clientHost.removeStreamVisitor(visitor)
                streamVisitor = null
            }

            gate.release()
            gate.releaseForwarded.get(10, TimeUnit.SECONDS)
            firstClose.get(15, TimeUnit.SECONDS)
            assertTrue(first.closeFuture().isDone, "Releasing the gate must finish the physical close")
            assertEquals(1, gate.releaseForwardCount.get(), "The held close must be forwarded exactly once")
            assertEquals(1, gate.closeCalls.get(), "No second physical close should replace the held operation")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closeGate?.let { gate -> cleanupExplicitCloseResource(cleanupFailures) { gate.release() } }
            val heldClose = closeRequested
            if (heldClose != null) {
                cleanupExplicitCloseResource(cleanupFailures) { heldClose.get(15, TimeUnit.SECONDS) }
            }
            val visitor = streamVisitor
            val clientHost = client
            if (visitor != null && clientHost != null) {
                cleanupExplicitCloseResource(cleanupFailures) { clientHost.removeStreamVisitor(visitor) }
            }
            cleanupExplicitCloseResource(cleanupFailures) { stopExplicitCloseTestHosts(hosts) }

            val originalFailure = primaryFailure
            if (originalFailure != null) {
                cleanupFailures.forEach { cleanupFailure ->
                    if (cleanupFailure !== originalFailure) originalFailure.addSuppressed(cleanupFailure)
                }
            } else if (cleanupFailures.isNotEmpty()) {
                cleanupFailures.drop(1).forEach { cleanupFailure ->
                    if (cleanupFailure !== cleanupFailures[0]) cleanupFailures[0].addSuppressed(cleanupFailure)
                }
                throw cleanupFailures[0]
            }
        }
    }
}

private class ExplicitParentCloseGate : ChannelOutboundHandlerAdapter() {
    val intercepted = CompletableFuture<Unit>()
    val releaseForwarded = CompletableFuture<Unit>()
    val closeCalls = AtomicInteger()
    val releaseForwardCount = AtomicInteger()
    private val releaseRequested = AtomicBoolean(false)
    private val forwarded = AtomicBoolean(false)
    private val pendingClose = AtomicReference<HeldClose?>(null)

    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        if (closeCalls.incrementAndGet() != 1) {
            ctx.close(promise)
            return
        }

        check(pendingClose.compareAndSet(null, HeldClose(ctx, promise))) {
            "The first physical close was already captured"
        }
        intercepted.complete(Unit)
        forwardIfReleased()
    }

    fun release() {
        releaseRequested.set(true)
        forwardIfReleased()
    }

    private fun forwardIfReleased() {
        if (!releaseRequested.get()) return
        val held = pendingClose.get() ?: return
        if (!forwarded.compareAndSet(false, true)) return

        held.context.executor().execute {
            releaseForwardCount.incrementAndGet()
            try {
                held.context.close(held.promise)
                releaseForwarded.complete(Unit)
            } catch (failure: Throwable) {
                releaseForwarded.completeExceptionally(failure)
                throw failure
            }
        }
    }

    private data class HeldClose(
        val context: ChannelHandlerContext,
        val promise: ChannelPromise,
    )
}

private fun startExplicitCloseTestHost(hosts: MutableList<Host>): Host =
    host {
        transports { add(::TcpTransport) }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        protocols { add(Ping()) }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }.also { testHost ->
        hosts += testHost
        testHost.start().get(20, TimeUnit.SECONDS)
    }

private fun assertExplicitParentClosingFailure(
    future: CompletableFuture<*>,
    expectedMessage: String,
    label: String,
) {
    val failure = assertThrows(ExecutionException::class.java) {
        future.get(15, TimeUnit.SECONDS)
    }
    val cause = failure.cause
    assertEquals(
        ConnectionClosedException::class.java,
        cause?.javaClass,
        "The $label future must fail with ConnectionClosedException",
    )
    assertEquals(expectedMessage, cause?.message, "The $label failure must retain the complete close-intent message")
}

private fun cleanupExplicitCloseResource(failures: MutableList<Throwable>, action: () -> Unit) {
    try {
        action()
    } catch (failure: Throwable) {
        failures += failure
    }
}

private fun stopExplicitCloseTestHosts(hosts: List<Host>) {
    var firstFailure: Throwable? = null
    hosts.asReversed().forEach { testHost ->
        try {
            testHost.stop().get(15, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            val originalFailure = firstFailure
            if (originalFailure == null) {
                firstFailure = failure
            } else if (failure !== originalFailure) {
                originalFailure.addSuppressed(failure)
            }
        }
    }
    firstFailure?.let { throw AssertionError("Failed to stop all explicit-parent test hosts", it) }
}
