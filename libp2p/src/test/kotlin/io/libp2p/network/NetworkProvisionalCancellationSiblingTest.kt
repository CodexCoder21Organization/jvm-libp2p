package io.libp2p.network

import io.libp2p.core.*
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class NetworkProvisionalCancellationSiblingTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun cancelledQueuedChildLeavesAcceptedSiblingUsable(mplex: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responders = AtomicInteger()
        val responderStarted = CompletableFuture<Unit>()
        val binding = object : StrictProtocolBinding<Unit>(
            "/test/cancelled-sibling/1.0.0",
            object : ProtocolHandler<Unit>(1, 1) {
                override fun onStartInitiator(stream: Stream) = CompletableFuture.completedFuture(Unit)
                override fun onStartResponder(stream: Stream): CompletableFuture<Unit> {
                    responders.incrementAndGet()
                    responderStarted.complete(Unit)
                    return CompletableFuture.completedFuture(Unit)
                }
            }
        ) {}
        fun node() = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
            muxers { +(if (mplex) StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()) }
            protocols {
                +Echo()
                +binding
            }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val server = node()
        val client = node()
        try {
            server.start().get(10, SECONDS)
            client.start().get(10, SECONDS)
            val parent = client.network.connect(server.peerId, server.listenAddresses().single()).get(5, SECONDS)
            val accepted = client.newStream<EchoController>(listOf("/test/echo"), parent)
            val echo = accepted.controller.get(5, SECONDS)
            accepted.stream.get(5, SECONDS).pushHandler(
                "hold-sibling-write",
                object : ChannelOutboundHandlerAdapter() {
                    private var first = true
                    override fun write(ctx: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
                        if (first) {
                            first = false
                            entered.countDown()
                            check(release.await(10, SECONDS)) { "The scenario must release the accepted sibling's write" }
                        }
                        ctx.write(message, promise)
                    }
                }
            )
            val pendingEcho = echo.echo("accepted sibling before cancellation")
            assertTrue(entered.await(5, SECONDS), "The accepted sibling must hold the parent event loop before queued child creation")
            val hold = checkNotNull(client.network.holdConnection(parent).get(5, SECONDS))
            val cancelled = client.newStream<Unit>(listOf("/test/cancelled-sibling/1.0.0"), hold)
            assertTrue(cancelled.controller.cancel(false), "The queued child must cancel before the accepted sibling resumes")
            release.countDown()
            assertEquals("accepted sibling before cancellation", pendingEcho.get(5, SECONDS))
            assertEquals("accepted sibling after cancellation", echo.echo("accepted sibling after cancellation").get(5, SECONDS))
            assertEquals(0, responders.get(), "The cancelled queued child must not start a responder before the later child opens")
            val later = client.newStream<Unit>(listOf("/test/cancelled-sibling/1.0.0"), parent)
            later.controller.get(5, SECONDS)
            // Initiator readiness does not order the peer responder callback.
            responderStarted.get(5, SECONDS)
            assertEquals(1, responders.get(), "Only the later child may start its responder; the cancelled queued child must not start")
            assertFalse(parent.closeFuture().isDone, "Cancelling a provisional child must leave its accepted sibling's parent open")
            assertFalse(accepted.stream.get(5, SECONDS).closeFuture().isDone, "The accepted sibling must remain open")
            later.stream.get(5, SECONDS).close().get(5, SECONDS)
            accepted.stream.get(5, SECONDS).close().get(5, SECONDS)
        } finally {
            release.countDown()
            try {
                client.stop().get(10, SECONDS)
            } finally {
                server.stop().get(10, SECONDS)
            }
        }
    }
}
