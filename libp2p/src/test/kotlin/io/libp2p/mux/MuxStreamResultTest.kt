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
                    // Force this implementation's missing-attribute guard before the stream result is derived.
                    channel.attr(STREAM).set(null)
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
            assertTrue(channel.close().await(5, SECONDS), "The test child close must finish")
            assertTrue(channel.closeFuture().isDone, "The test must close its child channel")
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
                    // Force this implementation's missing-attribute guard before the stream result is derived.
                    channel.attr(STREAM).set(null)
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
