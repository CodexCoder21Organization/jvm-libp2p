package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.concurrent.ImmediateEventExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConnectionCloseRetryTest {
    // Netty 4.2 exposes explicit handler execution only through this deprecated overload.
    @Suppress("DEPRECATION")
    @Test
    fun explicitRetryClosesRealConnectionAfterFailureAndKeepsOrdinaryResult() {
        val server = retryCloseHost()
        val client = retryCloseHost()
        var parent: ConnectionOverNetty? = null
        val requests = AtomicInteger()
        val failure = IOException("Cannot close test connection: physical close attempt 1 failed.")
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val connection: Connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS)
            parent = connection as ConnectionOverNetty
            connection.nettyChannel.pipeline().addLast(
                ImmediateEventExecutor.INSTANCE,
                "fail-first-close",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        if (requests.incrementAndGet() == 1) promise.setFailure(failure) else ctx.close(promise)
                    }
                }
            )
            val original = connection.close()
            assertSame(failure, retryCloseFailure(original))
            assertTrue(connection.nettyChannel.isOpen, "The injected failed close must leave the real socket open.")
            assertSame(failure, retryCloseFailure(connection.close()))
            assertEquals(1, requests.get(), "Ordinary close must keep its original result without another physical attempt.")

            connection.retryClose().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            assertFalse(connection.nettyChannel.isOpen, "An explicit retry after failure must close the real socket.")
            assertEquals(2, requests.get(), "The explicit retry must submit exactly one new physical close.")
            assertSame(failure, retryCloseFailure(original))
            assertSame(failure, retryCloseFailure(connection.close()))
            connection.retryClose().get(5, TimeUnit.SECONDS)
            assertEquals(2, requests.get(), "A successful retry is terminal; later retries must not submit another close.")
        } finally {
            try {
                cleanupRetryConnection(parent, "fail-first-close")
            } finally {
                CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun retryStartsInitialCloseAndSuccessRemainsTerminal() {
        withRetryConnection { connection, _ ->
            val requests = AtomicInteger()
            installRetryCloseHandler(connection) { ctx, promise ->
                requests.incrementAndGet()
                ctx.close(promise)
            }
            connection.retryClose().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            connection.close().get(5, TimeUnit.SECONDS)
            connection.retryClose().get(5, TimeUnit.SECONDS)
            assertEquals(1, requests.get(), "Successful initial close must remain terminal for both APIs.")
            assertFalse(connection.nettyChannel.isOpen)
        }
    }

    @Test
    fun firstRetryPublishesOwnershipBeforeItsPhysicalHandlerReenters() {
        withRetryConnection { connection, _ ->
            val requests = AtomicInteger()
            val reentered = AtomicReference<CompletableFuture<Unit>>()
            val failure = IOException("Cannot close test connection: physical close attempt 1 failed.")
            installRetryCloseHandler(connection) { ctx, promise ->
                if (requests.incrementAndGet() == 1) {
                    reentered.set(connection.retryClose())
                    assertFalse(reentered.get().isDone, "First-dispatch re-entry must share the pending initial attempt.")
                    promise.setFailure(failure)
                } else {
                    ctx.close(promise)
                }
            }
            assertSame(failure, retryCloseFailure(connection.retryClose()))
            assertSame(failure, retryCloseFailure(reentered.get()))
            assertEquals(1, requests.get(), "First-dispatch re-entry must not issue another physical close.")
            connection.retryClose().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            assertEquals(2, requests.get())
            assertSame(failure, retryCloseFailure(connection.close()))
        }
    }

    @Test
    fun transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult() {
        withRetryConnection { connection, _ ->
            val requests = AtomicInteger()
            val failure = IOException("Cannot close test connection: physical close attempt 1 failed.")
            installRetryCloseHandler(connection) { ctx, promise ->
                if (requests.incrementAndGet() == 1) promise.setFailure(failure) else ctx.close(promise)
            }
            assertSame(failure, retryCloseFailure(connection.transport().close()))
            assertSame(failure, retryCloseFailure(connection.close()))
            connection.retryClose().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            assertEquals(2, requests.get(), "Retry must recover a physical close first owned by transport shutdown.")
            assertSame(failure, retryCloseFailure(connection.close()))
        }
    }

    @Test
    fun retryFirstStartsOnlyOneAttemptEvenWhenItFailsImmediately() {
        withRetryConnection { connection, _ ->
            val requests = AtomicInteger()
            val failure = IOException("Cannot close test connection: physical close attempt 1 failed.")
            installRetryCloseHandler(connection) { _, promise ->
                requests.incrementAndGet()
                promise.setFailure(failure)
            }
            val first = connection.retryClose()
            assertSame(failure, retryCloseFailure(first))
            assertEquals(1, requests.get(), "A retry on an untouched connection starts only its initial close.")
            assertSame(failure, retryCloseFailure(connection.close()))
            assertEquals(1, requests.get(), "Ordinary close must share the initial explicit caller's failure.")
            assertSame(failure, retryCloseFailure(connection.retryClose()))
            assertEquals(2, requests.get(), "Only a later explicit call may request attempt two.")
        }
    }

    @Test
    fun retrySharesPendingInitialCloseAndCanRecoverItsFailure() {
        withRetryConnection { connection, _ ->
            val held = CopyOnWriteArrayList<Pair<ChannelHandlerContext, ChannelPromise>>()
            installRetryCloseHandler(connection) { ctx, promise -> held.add(ctx to promise) }
            val first = connection.close()
            val retry = connection.retryClose()
            assertEquals(1, held.size, "A pending original close must not be duplicated by retry.")
            assertFalse(first.isDone)
            assertFalse(retry.isDone)
            val failure = IOException("Cannot close test connection: physical close attempt 1 failed.")
            held[0].second.setFailure(failure)
            assertSame(failure, retryCloseFailure(first))
            assertSame(failure, retryCloseFailure(retry))
            val next = connection.retryClose()
            assertEquals(2, held.size, "Retry may submit a new close only after the original failure settled.")
            held[1].let { (ctx, promise) -> ctx.close(promise) }
            next.get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            assertFalse(connection.nettyChannel.isOpen)
        }
    }

    @Test
    fun concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges() {
        withRetryConnection { connection, _ ->
            val held = CopyOnWriteArrayList<Pair<ChannelHandlerContext, ChannelPromise>>()
            val requests = AtomicInteger()
            val failure = IOException("Cannot close test connection: physical close attempt 1 failed.")
            installRetryCloseHandler(connection) { ctx, promise ->
                if (requests.incrementAndGet() == 1) promise.setFailure(failure) else held.add(ctx to promise)
            }
            assertSame(failure, retryCloseFailure(connection.close()))
            val workers = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            try {
                // Hold the actual physical attempt, so overlap does not depend on host speed.
                val callers = (0 until 64).map {
                    workers.submit<List<CompletableFuture<Unit>>> {
                        check(start.await(5, TimeUnit.SECONDS)) { "Concurrent close callers were never released." }
                        (0 until 128).map {
                            assertSame(failure, retryCloseFailure(connection.close()))
                            connection.retryClose()
                        }
                    }
                }
                start.countDown()
                val observations = callers.flatMap { it.get(5, TimeUnit.SECONDS) }
                assertEquals(8192, observations.size)
                assertEquals(2, requests.get(), "8192 explicit callers must share one pending retry after the initial failure.")
                assertEquals(1, held.size)
                observations[0].cancel(false)
                observations[1].complete(Unit)
                observations[2].completeExceptionally(IOException("Caller changed its own observation."))
                assertTrue(observations.drop(3).none { it.isDone }, "Caller mutation must not settle another retry observer.")
                assertSame(failure, retryCloseFailure(connection.transport().close()))
                held.single().let { (ctx, promise) -> ctx.close(promise) }
                CompletableFuture.allOf(*observations.drop(3).toTypedArray()).get(5, TimeUnit.SECONDS)
                connection.closeFuture().get(5, TimeUnit.SECONDS)
                assertTrue(observations[0].isCancelled)
                assertTrue(observations[2].isCompletedExceptionally)
                connection.retryClose().get(5, TimeUnit.SECONDS)
                assertSame(failure, retryCloseFailure(connection.close()))
                assertEquals(2, requests.get(), "Success must remain terminal across subsequent retry and ordinary callers.")
            } finally {
                workers.shutdownNow()
                try {
                    assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Concurrent close callers must all stop.")
                } finally {
                    held.filter { !it.second.isDone }.forEach { (ctx, promise) -> ctx.close(promise) }
                }
            }
        }
    }

    @Test
    fun repeatedFailuresReportEachAttemptAndPermitRecovery() {
        withRetryConnection { connection, _ ->
            val requests = AtomicInteger()
            installRetryCloseHandler(connection) { ctx, promise ->
                val attempt = requests.incrementAndGet()
                if (attempt <= 1000) {
                    promise.setFailure(IOException("Cannot close test connection: physical close attempt $attempt failed."))
                } else {
                    ctx.close(promise)
                }
            }
            val original = connection.close()
            // Serial by contract: every next explicit call follows the previous settled failure.
            for (attempt in 1..1000) {
                val current = if (attempt == 1) original else connection.retryClose()
                val error = assertThrows(ExecutionException::class.java) { current.get(5, TimeUnit.SECONDS) }
                var cause: Throwable = error
                while (cause.cause != null) cause = cause.cause!!
                assertEquals("Cannot close test connection: physical close attempt $attempt failed.", cause.message)
                assertEquals(attempt, requests.get(), "A settled failure must represent exactly one physical attempt.")
                retryCloseFailure(connection.close())
                assertTrue(connection.nettyChannel.isOpen)
            }
            connection.retryClose().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            assertEquals(1001, requests.get())
            retryCloseFailure(original)
            retryCloseFailure(connection.close())
        }
    }

    @Test
    fun failureCallbackAndPhysicalHandlerCanReenterWithoutDuplicatingRetry() {
        withRetryConnection { connection, _ ->
            val requests = AtomicInteger()
            val failure = IOException("Cannot close test connection: physical close attempt 1 failed.")
            val heldFirst = AtomicReference<ChannelPromise>()
            val reentered = AtomicReference<CompletableFuture<Unit>>()
            installRetryCloseHandler(connection) { ctx, promise ->
                if (requests.incrementAndGet() == 1) {
                    heldFirst.set(promise)
                } else {
                    reentered.set(connection.retryClose())
                    assertFalse(reentered.get().isDone, "Re-entry must observe the already claimed physical retry.")
                    ctx.close(promise)
                }
            }
            val original = connection.close()
            val recovered = original.handle { _, _ -> connection.retryClose() }.thenCompose { it }
            heldFirst.get().setFailure(failure)
            recovered.get(5, TimeUnit.SECONDS)
            reentered.get().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            assertEquals(2, requests.get())
            assertSame(failure, retryCloseFailure(connection.close()))
        }
    }

    @Test
    fun retryAfterPeerClosedFollowingFailureDoesNotRequestAnotherPhysicalClose() {
        withRetryConnection { connection, peer ->
            val requests = AtomicInteger()
            val failure = IOException("Cannot close test connection: physical close attempt 1 failed.")
            installRetryCloseHandler(connection) { _, promise ->
                requests.incrementAndGet()
                promise.setFailure(failure)
            }
            assertSame(failure, retryCloseFailure(connection.close()))
            // The peer's close reaches Netty's inbound path, independently of the failed outbound close.
            peer.close().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            connection.retryClose().get(5, TimeUnit.SECONDS)
            assertEquals(1, requests.get(), "A physically closed channel must not receive another close request.")
            assertSame(failure, retryCloseFailure(connection.close()))
        }
    }
}

private fun retryCloseHost(onConnection: (Connection) -> Unit = {}) = host {
    connectionHandlers { add(ConnectionHandler.create(onConnection)) }
    transports { add(::TcpTransport) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    network { listen("/ip4/127.0.0.1/tcp/0") }
}

private fun retryCloseFailure(future: CompletableFuture<Unit>): Throwable {
    var failure: Throwable = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
    while (failure.cause != null) failure = failure.cause!!
    assertEquals("Cannot close test connection: physical close attempt 1 failed.", failure.message)
    return failure
}

private fun withRetryConnection(body: (ConnectionOverNetty, Connection) -> Unit) {
    val remote = CompletableFuture<Connection>()
    val server = retryCloseHost { remote.complete(it) }
    val client = retryCloseHost()
    var parent: ConnectionOverNetty? = null
    try {
        server.start().get(5, TimeUnit.SECONDS)
        client.start().get(5, TimeUnit.SECONDS)
        val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
            .get(5, TimeUnit.SECONDS) as ConnectionOverNetty
        parent = connection
        body(connection, remote.get(5, TimeUnit.SECONDS))
    } finally {
        try {
            cleanupRetryConnection(parent, "retry-close-control")
        } finally {
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }
}

// Netty 4.2 exposes explicit handler execution only through this deprecated overload.
@Suppress("DEPRECATION")
private fun installRetryCloseHandler(
    connection: ConnectionOverNetty,
    action: (ChannelHandlerContext, ChannelPromise) -> Unit
) {
    connection.nettyChannel.pipeline().addLast(
        ImmediateEventExecutor.INSTANCE,
        "retry-close-control",
        object : ChannelOutboundHandlerAdapter() {
            override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) = action(ctx, promise)
        }
    )
}

private fun cleanupRetryConnection(connection: ConnectionOverNetty?, handlerName: String) {
    connection?.let {
        // Pipeline teardown uses this same event loop; the check and removal must be one task.
        it.nettyChannel.eventLoop().submit {
            if (it.nettyChannel.pipeline().context(handlerName) != null) {
                it.nettyChannel.pipeline().remove(handlerName)
            }
        }.get(5, TimeUnit.SECONDS)
        it.nettyChannel.close().get(5, TimeUnit.SECONDS)
        it.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
    }
}
