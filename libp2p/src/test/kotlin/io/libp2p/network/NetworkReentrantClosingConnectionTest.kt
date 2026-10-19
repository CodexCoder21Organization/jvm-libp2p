package io.libp2p.network

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NetworkReentrantClosingConnectionTest {
    private val hosts = mutableListOf<Host>()

    @Test
    fun `connect reentered from a closing connection handler dials a fresh parent`() {
        val clientConnections = ConnectionRecorder()
        val serverConnections = ConnectionRecorder()
        val closeGate = AsyncCloseGate()
        val firstClientCallback = AtomicBoolean()
        val firstClientConnection = AtomicReference<Connection?>()
        val closeRequested = AtomicReference<CompletableFuture<Unit>?>()
        val reentrantConnect = AtomicReference<CompletableFuture<Connection>?>()
        val callbackFailure = AtomicReference<Throwable?>()
        val callbackComplete = CompletableFuture<Unit>()
        val clientReference = AtomicReference<Host?>()
        var initialConnect: CompletableFuture<Connection>? = null
        var initialOutcomeInspected = false
        var reentrantOutcomeInspected = false
        var primaryFailure: Throwable? = null

        try {
            val server = startReentrantTestHost(serverConnections::record, hosts)
            val serverAddress = server.listenAddresses().single()
            val clientConnectionHandler: (Connection) -> Unit = { connection ->
                clientConnections.record(connection)
                if (firstClientCallback.compareAndSet(false, true)) {
                    firstClientConnection.set(connection)
                    try {
                        val nettyChannel = (connection as ConnectionOverNetty).nettyChannel
                        // Pipeline addition and close are ordered on this channel's event loop.
                        nettyChannel.pipeline().addFirst("heldPhysicalClose", closeGate)
                        closeRequested.set(connection.close())
                        // Use the normal two-argument overload: no caller pre-handler is supplied.
                        val callbackClient = clientReference.get()
                            ?: throw IllegalStateException("The client host was not assigned before its first dial")
                        reentrantConnect.set(callbackClient.network.connect(server.peerId, serverAddress))
                    } catch (failure: Throwable) {
                        callbackFailure.set(failure)
                    } finally {
                        callbackComplete.complete(Unit)
                    }
                }
            }
            val client = startReentrantTestHost(clientConnectionHandler, hosts)
            clientReference.set(client)

            // This is the first public dial. Its connection handler closes and re-enters connect.
            val firstConnect = client.network.connect(server.peerId, serverAddress)
            initialConnect = firstConnect

            callbackComplete.get(15, TimeUnit.SECONDS)
            callbackFailure.get()?.let { throw AssertionError("The first connection callback failed", it) }
            val closingConnection = firstClientConnection.get()
                ?: throw AssertionError("The first client connection callback did not record its connection")
            val closingChannel = (closingConnection as ConnectionOverNetty).nettyChannel
            val firstClose = closeRequested.get()
                ?: throw AssertionError("The first connection callback did not request close")
            val reentrantFuture = reentrantConnect.get()
                ?: throw AssertionError("The first connection callback did not start its reentrant connect")

            closeGate.intercepted.get(10, TimeUnit.SECONDS)
            assertThat(closeGate.closeCalls.get())
                .describedAs("the first physical close must reach the asynchronous gate exactly once")
                .isEqualTo(1)
            assertThat(closingConnection.closeFuture().isDone)
                .describedAs("the first physical close must remain held during the reentrant dial")
                .isFalse()
            assertThat(firstClose.isDone)
                .describedAs("the first public close future must remain pending while the gate holds the channel")
                .isFalse()

            val replacement = try {
                reentrantFuture.get(20, TimeUnit.SECONDS)
            } catch (failure: ExecutionException) {
                reentrantOutcomeInspected = true
                throw failure
            }
            reentrantOutcomeInspected = true
            val replacementChannel = (replacement as ConnectionOverNetty).nettyChannel
            assertThat(replacement)
                .describedAs("the reentrant public connect must not join the first connection while it is closing")
                .isNotSameAs(closingConnection)
            assertThat(replacementChannel)
                .describedAs("the reentrant dial must create an independent physical channel")
                .isNotSameAs(closingChannel)
            assertThat(replacement.secureSession().remoteId).isEqualTo(server.peerId)

            val secondClientConnection = clientConnections.second.get(15, TimeUnit.SECONDS)
            val firstServerConnection = serverConnections.first.get(15, TimeUnit.SECONDS)
            val secondServerConnection = serverConnections.second.get(15, TimeUnit.SECONDS)
            assertThat(secondClientConnection).isSameAs(replacement)
            assertThat(clientConnections.established).containsExactly(closingConnection, replacement)
            assertThat(serverConnections.established).containsExactly(firstServerConnection, secondServerConnection)
            assertThat(serverConnections.established.map { it.secureSession().remoteId })
                .containsExactly(client.peerId, client.peerId)

            val replacementPing = Ping().dial(client, server.peerId, serverAddress)
            val replacementStream = replacementPing.stream.get(15, TimeUnit.SECONDS)
            val replacementController: PingController = replacementPing.controller.get(15, TimeUnit.SECONDS)
            assertThat(replacementStream.connection)
                .describedAs("a real Ping on the fresh parent must use the replacement connection")
                .isSameAs(replacement)
            val pingBeforeOldClose = replacementController.ping().get(15, TimeUnit.SECONDS)
            assertThat(pingBeforeOldClose).isGreaterThanOrEqualTo(0L)
            assertThat(closingConnection.closeFuture().isDone)
                .describedAs("the replacement Ping must complete while the old physical close is still held")
                .isFalse()

            closeGate.release()
            closeGate.releaseForwarded.get(10, TimeUnit.SECONDS)
            firstClose.get(15, TimeUnit.SECONDS)
            assertThat(closingConnection.closeFuture().isDone).isTrue()
            assertThat(replacement.closeFuture().isDone)
                .describedAs("releasing the old close must leave the replacement parent open")
                .isFalse()

            val pingAfterOldClose = replacementController.ping().get(15, TimeUnit.SECONDS)
            assertThat(pingAfterOldClose).isGreaterThanOrEqualTo(0L)

            var initialConnection: Connection? = null
            var initialFailure: Throwable? = null
            try {
                initialConnection = firstConnect.get(20, TimeUnit.SECONDS)
                initialOutcomeInspected = true
            } catch (failure: ExecutionException) {
                initialOutcomeInspected = true
                initialFailure = failure.cause ?: failure
            }
            if (initialFailure != null) {
                val causeDescription = describeCauseChain(initialFailure!!)
                assertThat(causeChain(initialFailure!!).any { it is ConnectionClosedException })
                    .describedAs(
                        "the original dial may fail only because its closing parent was rejected; " +
                            "causes: $causeDescription"
                    )
                    .isTrue()
            } else {
                assertThat(initialConnection)
                    .describedAs("the original dial may resolve only to the fresh retained replacement")
                    .isSameAs(replacement)
            }
            assertThat(initialConnection)
                .describedAs("the original dial must never return the exact parent already committed to close")
                .isNotSameAs(closingConnection)

            val laterReuse = client.network.connect(server.peerId, serverAddress).get(20, TimeUnit.SECONDS)
            assertThat(laterReuse)
                .describedAs("a later public connect must reuse the fresh replacement without another dial")
                .isSameAs(replacement)
            assertThat(clientConnections.established).containsExactly(closingConnection, replacement)
            assertThat(serverConnections.established).containsExactly(firstServerConnection, secondServerConnection)
            assertThat(closeGate.closeCalls.get()).isEqualTo(1)
            assertThat(closeGate.releaseForwardCount.get()).isEqualTo(1)
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

            cleanup { closeGate.release() }
            cleanup { closeRequested.get()?.get(15, TimeUnit.SECONDS) }
            cleanup { stopReentrantTestHosts(hosts) }
            if (!initialOutcomeInspected) cleanup { awaitReentrantTestFuture(initialConnect) }
            if (!reentrantOutcomeInspected) cleanup { awaitReentrantTestFuture(reentrantConnect.get()) }

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

private fun startReentrantTestHost(onConnection: (Connection) -> Unit, hosts: MutableList<Host>): Host =
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

private fun stopReentrantTestHosts(hosts: List<Host>) {
    val failures = mutableListOf<Throwable>()
    hosts.asReversed().forEach { testHost ->
        try {
            testHost.stop().get(15, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            failures += failure
        }
    }
    if (failures.isNotEmpty()) {
        failures.drop(1).forEach { failure ->
            if (failure !== failures[0]) failures[0].addSuppressed(failure)
        }
        throw AssertionError("Failed to stop all reentrant test hosts", failures[0])
    }
}

private fun awaitReentrantTestFuture(future: CompletableFuture<*>?) {
    future?.get(15, TimeUnit.SECONDS)
}

private fun causeChain(failure: Throwable): List<Throwable> {
    val causes = mutableListOf<Throwable>()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = failure
    while (current != null && seen.add(current)) {
        causes += current
        current = current.cause
    }
    return causes
}

private fun describeCauseChain(failure: Throwable): String =
    causeChain(failure).joinToString(" -> ") { it.toString() }
