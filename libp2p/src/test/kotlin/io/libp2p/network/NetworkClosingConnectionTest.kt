package io.libp2p.network

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NetworkClosingConnectionTest {
    private val hosts = mutableListOf<Host>()

    @Test
    fun `connect does not reuse a connection whose physical close is still pending`() {
        val clientConnections = ConnectionRecorder()
        val serverConnections = ConnectionRecorder()
        var closeGate: AsyncCloseGate? = null
        var closeRequested: CompletableFuture<Unit>? = null
        var primaryFailure: Throwable? = null

        try {
            val client = startHost(clientConnections::record, hosts)
            val server = startHost(serverConnections::record, hosts)
            val serverAddress = server.listenAddresses().single()

            val first = client.network.connect(server.peerId, serverAddress).get(20, TimeUnit.SECONDS)
            val firstClientHandshake = clientConnections.first.get(10, TimeUnit.SECONDS)
            val firstServerHandshake = serverConnections.first.get(10, TimeUnit.SECONDS)

            assertThat(first.secureSession().remoteId).isEqualTo(server.peerId)
            assertThat(firstClientHandshake).isSameAs(first)
            assertThat(clientConnections.established).containsExactly(first)
            assertThat(serverConnections.established).containsExactly(firstServerHandshake)
            assertThat(firstServerHandshake.secureSession().remoteId).isEqualTo(client.peerId)

            val initialPing = Ping().dial(client, server.peerId, serverAddress)
            val initialStream = initialPing.stream.get(15, TimeUnit.SECONDS)
            val initialController: PingController = initialPing.controller.get(15, TimeUnit.SECONDS)
            val successfulPingRoundTrips = mutableListOf<Long>()
            successfulPingRoundTrips += initialController.ping().get(15, TimeUnit.SECONDS)
            assertThat(initialStream.connection)
                .describedAs("the first real Ping stream must use the first connected transport")
                .isSameAs(first)

            val firstNettyChannel = (first as ConnectionOverNetty).nettyChannel
            val gate = AsyncCloseGate()
            closeGate = gate
            firstNettyChannel.eventLoop().submit {
                firstNettyChannel.pipeline().addFirst("heldPhysicalClose", gate)
            }.get(10, TimeUnit.SECONDS)

            val firstCloseRequested = first.close()
            closeRequested = firstCloseRequested
            gate.intercepted.get(10, TimeUnit.SECONDS)

            assertThat(gate.closeCalls.get())
                .describedAs("one public Connection.close call should reach the physical close gate once")
                .isEqualTo(1)
            assertThat(first.closeFuture().isDone)
                .describedAs("the physical close future must remain incomplete while the gate holds it")
                .isFalse()
            assertThat(firstCloseRequested.isDone)
                .describedAs("Connection.close must still be pending until the held physical close is released")
                .isFalse()

            val replacement = client.network.connect(server.peerId, serverAddress).get(20, TimeUnit.SECONDS)
            assertThat(replacement)
                .describedAs(
                    "a new public Network.connect call must not return the connection whose close was requested"
                )
                .isNotSameAs(first)
            val replacementNettyChannel = (replacement as ConnectionOverNetty).nettyChannel
            assertThat(replacementNettyChannel)
                .describedAs("the replacement must have a different physical Netty channel")
                .isNotSameAs(firstNettyChannel)
            assertThat(replacement.secureSession().remoteId).isEqualTo(server.peerId)

            val secondClientHandshake = clientConnections.second.get(15, TimeUnit.SECONDS)
            val secondServerHandshake = serverConnections.second.get(15, TimeUnit.SECONDS)
            assertThat(secondClientHandshake)
                .describedAs("the client must complete exactly one replacement Noise/Yamux connection")
                .isSameAs(replacement)
            assertThat(clientConnections.established).containsExactly(first, replacement)
            assertThat(serverConnections.established).containsExactly(firstServerHandshake, secondServerHandshake)
            assertThat(serverConnections.established.map { it.secureSession().remoteId })
                .containsExactly(client.peerId, client.peerId)

            val replacementPing = Ping().dial(client, server.peerId, serverAddress)
            val replacementStream = replacementPing.stream.get(15, TimeUnit.SECONDS)
            val replacementController: PingController = replacementPing.controller.get(15, TimeUnit.SECONDS)
            assertThat(replacementStream.connection)
                .describedAs("the replacement Ping stream must use the replacement physical connection")
                .isSameAs(replacement)
            successfulPingRoundTrips += replacementController.ping().get(15, TimeUnit.SECONDS)

            assertThat(gate.closeCalls.get())
                .describedAs("the old physical close must still be held until the test releases it")
                .isEqualTo(1)
            assertThat(first.closeFuture().isDone)
                .describedAs("connecting and pinging on the replacement must not release the old close gate")
                .isFalse()

            gate.release()
            gate.releaseForwarded.get(10, TimeUnit.SECONDS)
            firstCloseRequested.get(15, TimeUnit.SECONDS)

            assertThat(first.closeFuture().isDone)
                .describedAs("releasing the gate must finish the old physical close")
                .isTrue()
            assertThat(replacement.closeFuture().isDone)
                .describedAs("completing the old close must leave the replacement connection open")
                .isFalse()
            successfulPingRoundTrips += replacementController.ping().get(15, TimeUnit.SECONDS)

            assertThat(successfulPingRoundTrips).hasSize(3)
            successfulPingRoundTrips.forEach { roundTrip ->
                assertThat(roundTrip).isGreaterThanOrEqualTo(0L)
            }
            assertThat(gate.closeCalls.get()).isEqualTo(1)
            assertThat(gate.releaseForwardCount.get()).isEqualTo(1)
            assertThat(clientConnections.established).containsExactly(first, replacement)
            assertThat(serverConnections.established).containsExactly(firstServerHandshake, secondServerHandshake)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }

            cleanup { closeGate?.release() }
            cleanup { closeRequested?.get(15, TimeUnit.SECONDS) }
            cleanup { stopHosts(hosts) }

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

    private class ConnectionRecorder {
        val established = CopyOnWriteArrayList<Connection>()
        val first = CompletableFuture<Connection>()
        val second = CompletableFuture<Connection>()
        private val count = AtomicInteger()

        fun record(connection: Connection) {
            established += connection
            when (count.incrementAndGet()) {
                1 -> first.complete(connection)
                2 -> second.complete(connection)
            }
        }
    }

    private class AsyncCloseGate : ChannelOutboundHandlerAdapter() {
        val intercepted = CompletableFuture<Unit>()
        val releaseForwarded = CompletableFuture<Unit>()
        val closeCalls = AtomicInteger()
        val releaseForwardCount = AtomicInteger()
        private val releaseRequested = AtomicBoolean()
        private val forwarded = AtomicBoolean()
        private val pendingClose = AtomicReference<HeldClose?>()

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
            val promise: ChannelPromise
        )
    }
}

private fun startHost(onConnection: (Connection) -> Unit, hosts: MutableList<Host>): Host =
    host {
        transports { add(::TcpTransport) }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        protocols { add(Ping()) }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        connectionHandlers { add(ConnectionHandler.create(onConnection)) }
    }.also { testHost ->
        hosts += testHost
        testHost.start().get(20, TimeUnit.SECONDS)
    }

private fun stopHosts(hosts: List<Host>) {
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
    firstFailure?.let { throw AssertionError("Failed to stop all test hosts", it) }
}
