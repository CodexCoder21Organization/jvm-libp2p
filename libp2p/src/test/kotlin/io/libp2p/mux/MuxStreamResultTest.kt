package io.libp2p.mux

import io.libp2p.core.StreamHandler
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.STREAM
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.Channel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class MuxStreamResultTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun missingStreamAndSetupCauseFailsTheStreamResult(mplex: Boolean) {
        val session = AtomicReference<MuxHandler>()
        val childChannel = AtomicReference<Channel>()
        val muxer = if (mplex) StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        val observedMuxer = StreamMuxerProtocol { multistream, protocols ->
            val delegate = muxer.createMuxer(multistream, protocols)
            object : StreamMuxer by delegate {
                override fun initChannel(ch: io.libp2p.core.P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> =
                    delegate.initChannel(ch, selectedProtocol).thenApply {
                        session.set(it as MuxHandler)
                        it
                    }
            }
        }
        val server = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +muxer }
            protocols { +Echo() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +observedMuxer }
            protocols { +Echo() }
        }
        try {
            server.start().awaitMuxResultStep("Server startup", 10)
            client.start().awaitMuxResultStep("Client startup", 10)
            client.network.connect(server.peerId, server.listenAddresses().single()).awaitMuxResultStep("Parent dial")
            val promise = session.get().createStream(
                StreamHandler { stream ->
                    val channel = (stream as StreamOverNetty).nettyChannel
                    childChannel.set(channel)
                    // This defensive check uses mux bookkeeping and needs rewriting if that bookkeeping changes.
                    channel.attr(STREAM).set(null)
                    assertTrue(channel.isOpen, "The child must still be open when its handler returns")
                    CompletableFuture.completedFuture(Unit)
                }
            )
            val failure = assertThrows(ExecutionException::class.java) { promise.stream.awaitMuxResultStep("Missing-attribute stream result") }
            val channel = childChannel.get()
            assertInstanceOf(IllegalStateException::class.java, failure.cause)
            assertEquals(
                "Stream setup completed without a Stream or a setup cause for channel ${channel.id()} (peer ${channel.remoteAddress()})",
                failure.cause?.message
            )
            assertEquals(Unit, promise.controller.awaitMuxResultStep("Completed stream handler"), "A completed handler result must keep its outcome")
            assertTrue(channel.closeFuture().await(5, SECONDS), "The library must close the child channel after the guard fails")
            assertTrue(channel.closeFuture().isDone, "The child close future must finish")
            assertFalse(channel.isOpen, "The library must leave no half-set-up child open")
        } finally {
            try {
                client.stop().awaitMuxResultStep("Client shutdown", 10)
            } finally {
                server.stop().awaitMuxResultStep("Server shutdown", 10)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun missingStreamAndSetupCauseFailsPendingControllerAndClosesChild(mplex: Boolean) {
        val session = AtomicReference<MuxHandler>()
        val childChannel = AtomicReference<Channel>()
        val handlerResult = CompletableFuture<Unit>()
        val muxer = if (mplex) StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        val observedMuxer = StreamMuxerProtocol { multistream, protocols ->
            val delegate = muxer.createMuxer(multistream, protocols)
            object : StreamMuxer by delegate {
                override fun initChannel(ch: io.libp2p.core.P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> =
                    delegate.initChannel(ch, selectedProtocol).thenApply {
                        session.set(it as MuxHandler)
                        it
                    }
            }
        }
        val server = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +muxer }
            protocols { +Echo() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +observedMuxer }
            protocols { +Echo() }
        }
        try {
            server.start().awaitMuxResultStep("Server startup", 10)
            client.start().awaitMuxResultStep("Client startup", 10)
            client.network.connect(server.peerId, server.listenAddresses().single()).awaitMuxResultStep("Parent dial")
            val promise = session.get().createStream(
                StreamHandler { stream ->
                    val channel = (stream as StreamOverNetty).nettyChannel
                    childChannel.set(channel)
                    // This defensive check uses mux bookkeeping and needs rewriting if that bookkeeping changes.
                    channel.attr(STREAM).set(null)
                    assertTrue(channel.isOpen, "The child must still be open when its handler returns")
                    handlerResult
                }
            )
            val failure = assertThrows(ExecutionException::class.java) { promise.stream.awaitMuxResultStep("Missing-attribute stream result") }
            val channel = childChannel.get()
            assertInstanceOf(IllegalStateException::class.java, failure.cause)
            assertEquals(
                "Stream setup completed without a Stream or a setup cause for channel ${channel.id()} (peer ${channel.remoteAddress()})",
                failure.cause?.message
            )
            val controllerFailure = assertThrows(ExecutionException::class.java) {
                promise.controller.awaitMuxResultStep("Missing-attribute controller result")
            }
            assertSame(failure.cause, controllerFailure.cause, "Both results must report the same setup failure")
            assertTrue(channel.closeFuture().await(5, SECONDS), "The guard must close its child channel")
            assertFalse(channel.isOpen, "The guard must leave no half-set-up child open")
            handlerResult.complete(Unit)
            val laterFailure = assertThrows(ExecutionException::class.java) {
                promise.controller.awaitMuxResultStep("Controller after late handler completion")
            }
            assertSame(failure.cause, laterFailure.cause, "A late handler result must preserve the setup failure")
        } finally {
            try {
                client.stop().awaitMuxResultStep("Client shutdown", 10)
            } finally {
                server.stop().awaitMuxResultStep("Server shutdown", 10)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun alreadyClosedChildFailsPendingController(mplex: Boolean) {
        val session = AtomicReference<MuxHandler>()
        val childChannel = AtomicReference<Channel>()
        val handlerResult = CompletableFuture<Unit>()
        val muxer = if (mplex) StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        val observedMuxer = StreamMuxerProtocol { multistream, protocols ->
            val delegate = muxer.createMuxer(multistream, protocols)
            object : StreamMuxer by delegate {
                override fun initChannel(ch: io.libp2p.core.P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> =
                    delegate.initChannel(ch, selectedProtocol).thenApply {
                        session.set(it as MuxHandler)
                        it
                    }
            }
        }
        val server = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +muxer }
            protocols { +Echo() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +observedMuxer }
            protocols { +Echo() }
        }
        try {
            server.start().awaitMuxResultStep("Server startup", 10)
            client.start().awaitMuxResultStep("Client startup", 10)
            client.network.connect(server.peerId, server.listenAddresses().single()).awaitMuxResultStep("Parent dial")
            val promise = session.get().createStream(
                StreamHandler { stream ->
                    val channel = (stream as StreamOverNetty).nettyChannel
                    childChannel.set(channel)
                    // This defensive check uses mux bookkeeping and needs rewriting if that bookkeeping changes.
                    channel.attr(STREAM).set(null)
                    assertTrue(channel.close().isDone, "The handler closes its child before returning")
                    handlerResult
                }
            )
            val failure = assertThrows(ExecutionException::class.java) { promise.stream.awaitMuxResultStep("Missing-attribute stream result") }
            val channel = childChannel.get()
            assertInstanceOf(IllegalStateException::class.java, failure.cause)
            assertEquals(
                "Stream setup completed without a Stream or a setup cause for channel ${channel.id()} (peer ${channel.remoteAddress()})",
                failure.cause?.message
            )
            val controllerFailure = assertThrows(ExecutionException::class.java) {
                promise.controller.awaitMuxResultStep("Missing-attribute controller result")
            }
            assertSame(failure.cause, controllerFailure.cause, "Both results must report the same setup failure")
            assertTrue(channel.closeFuture().await(5, SECONDS), "The guard must close its child channel")
            assertFalse(channel.isOpen, "The guard must leave no half-set-up child open")
            handlerResult.complete(Unit)
            val laterFailure = assertThrows(ExecutionException::class.java) {
                promise.controller.awaitMuxResultStep("Controller after late handler completion")
            }
            assertSame(failure.cause, laterFailure.cause, "A late handler result must preserve the setup failure")
        } finally {
            try {
                client.stop().awaitMuxResultStep("Client shutdown", 10)
            } finally {
                server.stop().awaitMuxResultStep("Server shutdown", 10)
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "false,open,failure,false", "true,open,failure,false",
        "false,open,cancelled,false", "true,open,cancelled,false",
        "false,closing,pending,false", "true,closing,pending,false",
        "false,closing,success,false", "true,closing,success,false",
        "false,closing,failure,false", "true,closing,failure,false",
        "false,closing,cancelled,false", "true,closing,cancelled,false",
        "false,closed,success,false", "true,closed,success,false",
        "false,closed,failure,false", "true,closed,failure,false",
        "false,closed,cancelled,false", "true,closed,cancelled,false",
        "false,open,pending,true", "true,open,pending,true"
    )
    fun guardSettlementIsIndependentOfChildAndControllerState(mplex: Boolean, childState: String, outcome: String, closeParent: Boolean) {
        val session = AtomicReference<MuxHandler>()
        val childChannel = AtomicReference<Channel>()
        val handlerResult = CompletableFuture<Unit>()
        val handlerFailure = IllegalArgumentException("The handler had already failed before the guard")
        val handlerEntered = java.util.concurrent.CountDownLatch(1)
        val releaseHandler = java.util.concurrent.CountDownLatch(1)
        val cancellationEntered = java.util.concurrent.CountDownLatch(1)
        val releaseCancellation = java.util.concurrent.CountDownLatch(1)
        val canceller = java.util.concurrent.Executors.newSingleThreadExecutor()
        val releaseClose = CompletableFuture<Unit>()
        val closeRequested = CompletableFuture<Unit>()
        val closingRequested = CompletableFuture<Unit>()
        val muxer = if (mplex) StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        val observedMuxer = StreamMuxerProtocol { multistream, protocols ->
            val delegate = muxer.createMuxer(multistream, protocols)
            object : StreamMuxer by delegate {
                override fun initChannel(ch: io.libp2p.core.P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> =
                    delegate.initChannel(ch, selectedProtocol).thenApply {
                        session.set(it as MuxHandler)
                        it
                    }
            }
        }
        val server = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +muxer }
            protocols { +Echo() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +observedMuxer }
            protocols { +Echo() }
        }
        try {
            server.start().awaitMuxResultStep("Server startup", 10)
            client.start().awaitMuxResultStep("Client startup", 10)
            val parent = client.network.connect(server.peerId, server.listenAddresses().single()).awaitMuxResultStep("Parent dial")
            val promise = session.get().createStream(
                StreamHandler { stream ->
                    val channel = (stream as StreamOverNetty).nettyChannel
                    childChannel.set(channel)
                    handlerEntered.countDown()
                    check(releaseHandler.await(10, SECONDS)) { "The test must release the stream handler" }
                    // This defensive check uses mux bookkeeping and needs rewriting if that bookkeeping changes.
                    channel.attr(STREAM).set(null)
                    if (childState != "closed") {
                        stream.pushHandler(object : io.netty.channel.ChannelOutboundHandlerAdapter() {
                            override fun handlerAdded(ctx: io.netty.channel.ChannelHandlerContext) {
                                if (childState == "closing") {
                                    assertFalse(channel.close().isDone, "The test holds a genuine child close before the guard runs")
                                    closingRequested.complete(Unit)
                                }
                            }
                            override fun close(ctx: io.netty.channel.ChannelHandlerContext, closePromise: io.netty.channel.ChannelPromise) {
                                closeRequested.complete(Unit)
                                releaseClose.thenRun { ctx.executor().execute { ctx.close(closePromise) } }
                            }
                        })
                    }
                    if (childState == "closed") {
                        assertTrue(channel.close().isDone, "The handler closes its child before returning")
                    }
                    when (outcome) {
                        "success" -> handlerResult.complete(Unit)
                        "failure" -> handlerResult.completeExceptionally(handlerFailure)
                    }
                    handlerResult
                }
            )
            assertTrue(handlerEntered.await(5, SECONDS), "The handler must enter before its caller chooses the controller state")
            val parentClose = if (closeParent) promise.stream.whenComplete { _, _ -> parent.close() } else null
            val cancelled = if (outcome == "cancelled") {
                promise.controller.whenComplete { _, _ ->
                    if (promise.controller.isCancelled) {
                        cancellationEntered.countDown()
                        check(releaseCancellation.await(10, SECONDS)) { "The test must release cancellation cleanup" }
                    }
                }
                canceller.submit<Boolean> { promise.controller.cancel(false) }.also {
                    assertTrue(cancellationEntered.await(5, SECONDS), "The controller must store cancellation before the handler returns")
                }
            } else {
                null
            }
            releaseHandler.countDown()
            val failure = assertThrows(ExecutionException::class.java) { promise.stream.awaitMuxResultStep("Missing-attribute stream result") }
            val channel = childChannel.get()
            assertInstanceOf(IllegalStateException::class.java, failure.cause)
            assertEquals(
                "Stream setup completed without a Stream or a setup cause for channel ${channel.id()} (peer ${channel.remoteAddress()})",
                failure.cause?.message
            )
            if (childState != "closed") {
                closeRequested.awaitMuxResultStep("Child close request")
                assertFalse(channel.closeFuture().isDone, "Both results must settle before the held close completes")
            }
            if (childState == "closing") {
                assertTrue(closingRequested.isDone, "The handler must request the held close before the guard runs")
            }
            when (outcome) {
                "pending" -> {
                    val controllerFailure = assertThrows(ExecutionException::class.java) {
                        promise.controller.awaitMuxResultStep("Guard controller before close completes")
                    }
                    assertSame(failure.cause, controllerFailure.cause, "Both pending results must report the same guard exception")
                    handlerResult.complete(Unit)
                    val laterFailure = assertThrows(ExecutionException::class.java) {
                        promise.controller.awaitMuxResultStep("Controller after late handler completion")
                    }
                    assertSame(failure.cause, laterFailure.cause)
                }
                "success" -> assertEquals(Unit, promise.controller.awaitMuxResultStep("Preserved successful controller"))
                "failure" -> {
                    val controllerFailure = assertThrows(ExecutionException::class.java) {
                        promise.controller.awaitMuxResultStep("Preserved failed controller")
                    }
                    assertSame(handlerFailure, controllerFailure.cause)
                    assertEquals("The handler had already failed before the guard", controllerFailure.cause?.message)
                }
                "cancelled" -> {
                    assertTrue(promise.controller.isCancelled, "The guard must preserve caller cancellation")
                    releaseCancellation.countDown()
                    assertTrue(cancelled!!.awaitMuxResultStep("Cancellation caller"))
                    handlerResult.complete(Unit)
                    assertTrue(promise.controller.isCancelled, "A late handler must preserve cancellation")
                }
            }
            releaseClose.complete(Unit)
            assertTrue(channel.closeFuture().await(5, SECONDS), "The released child close must finish")
            assertFalse(channel.isOpen)
            if (closeParent) {
                val callbackFailure = assertThrows(ExecutionException::class.java) { parentClose!!.awaitMuxResultStep("Parent close callback") }
                assertSame(failure.cause, callbackFailure.cause)
                parent.closeFuture().awaitMuxResultStep("Parent closed by stream result callback")
            } else {
                assertFalse(parent.closeFuture().isDone, "Guard cleanup must leave the parent usable")
            }
        } finally {
            releaseHandler.countDown()
            releaseCancellation.countDown()
            releaseClose.complete(Unit)
            canceller.shutdownNow()
            try {
                assertTrue(canceller.awaitTermination(5, SECONDS), "The cancellation helper must stop")
            } finally {
                try {
                    client.stop().awaitMuxResultStep("Client shutdown", 10)
                } finally {
                    server.stop().awaitMuxResultStep("Server shutdown", 10)
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "true,false", "false,true", "true,true")
    fun handlerFailureSettlesBothResults(mplex: Boolean, later: Boolean) {
        val session = AtomicReference<MuxHandler>()
        val childChannel = AtomicReference<Channel>()
        val setupFailure = IllegalStateException("The stream handler failed during setup")
        val handlerResult = CompletableFuture<Unit>()
        val muxer = if (mplex) StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        val observedMuxer = StreamMuxerProtocol { multistream, protocols ->
            val delegate = muxer.createMuxer(multistream, protocols)
            object : StreamMuxer by delegate {
                override fun initChannel(ch: io.libp2p.core.P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> =
                    delegate.initChannel(ch, selectedProtocol).thenApply {
                        session.set(it as MuxHandler)
                        it
                    }
            }
        }
        val server = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +muxer }
            protocols { +Echo() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +observedMuxer }
            protocols { +Echo() }
        }
        try {
            server.start().awaitMuxResultStep("Server startup", 10)
            client.start().awaitMuxResultStep("Client startup", 10)
            client.network.connect(server.peerId, server.listenAddresses().single()).awaitMuxResultStep("Parent dial")
            val promise = session.get().createStream(
                StreamHandler { stream ->
                    val channel = (stream as StreamOverNetty).nettyChannel
                    childChannel.set(channel)
                    if (!later) throw setupFailure
                    handlerResult
                }
            )
            if (later) {
                val stream = promise.stream.awaitMuxResultStep("Stream before later handler failure")
                assertFalse(promise.controller.isDone, "The controller must await the handler result")
                assertTrue(handlerResult.completeExceptionally(setupFailure))
                val failure = assertThrows(ExecutionException::class.java) {
                    promise.controller.awaitMuxResultStep("Later handler failure")
                }
                assertSame(setupFailure, failure.cause)
                assertEquals("The stream handler failed during setup", failure.cause?.message)
                assertSame(stream, promise.stream.awaitMuxResultStep("Preserved created stream"))
                stream.close().awaitMuxResultStep("Child close after handler failure")
            } else {
                for (result in listOf(promise.stream, promise.controller)) {
                    val failure = assertThrows(ExecutionException::class.java) {
                        result.awaitMuxResultStep("Synchronous handler failure")
                    }
                    assertSame(setupFailure, failure.cause)
                    assertEquals("The stream handler failed during setup", failure.cause?.message)
                }
            }
            val channel = childChannel.get()
            assertTrue(channel.closeFuture().await(5, SECONDS), "The failed test child must close")
            assertFalse(channel.isOpen)
            assertFalse(channel.parent().closeFuture().isDone, "A handler failure must leave its parent open")
        } finally {
            try {
                client.stop().awaitMuxResultStep("Client shutdown", 10)
            } finally {
                server.stop().awaitMuxResultStep("Server shutdown", 10)
            }
        }
    }
}

private fun <T> Future<T>.awaitMuxResultStep(step: String, seconds: Long = 5): T =
    try {
        get(seconds, SECONDS)
    } catch (failure: TimeoutException) {
        throw AssertionError("$step did not finish within $seconds seconds", failure)
    }
