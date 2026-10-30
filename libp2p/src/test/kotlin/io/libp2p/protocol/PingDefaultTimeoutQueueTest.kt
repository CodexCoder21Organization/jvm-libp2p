package io.libp2p.protocol

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PingDefaultTimeoutQueueTest {
    @Test
    fun completedPingsReleaseDefaultTimeoutQueueAcrossSchedulerReplacement() {
        val protocol = PingProtocol().apply { pingTimeout = Duration.ofDays(1) }
        val server = defaultQueueHost(Ping(), true)
        val client = defaultQueueHost(PingBinding(protocol), false)
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            repeat(2) {
                val opening = client.newStream<PingController>(
                    listOf("/ipfs/ping/1.0.0"),
                    server.peerId,
                    *server.listenAddresses().toTypedArray()
                )
                val stream = opening.stream.get(10, TimeUnit.SECONDS)
                val controller = opening.controller.get(10, TimeUnit.SECONDS)
                val scheduler = protocol.timeoutScheduler as ScheduledThreadPoolExecutor
                repeat(64) { controller.ping().get(10, TimeUnit.SECONDS) }
                assertThat(stream.closeFuture().isDone).isFalse()
                assertThat(scheduler.queue).`as`("64 completed real pings must return their default timeout tasks")
                    .isEmpty()
                stream.close().get(10, TimeUnit.SECONDS)
                // The close callback runs on the stream loop; a following task observes its release.
                val channel = (stream as io.libp2p.transport.implementation.P2PChannelOverNetty).nettyChannel
                channel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
                assertThat(scheduler.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
            }
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun closingPendingPingsReleasesDefaultQueueWhileASiblingIsLive() {
        val received = LinkedBlockingQueue<Unit>()
        val serverProtocol = object : PingProtocol() {
            override fun onStartResponder(stream: Stream): CompletableFuture<PingController> {
                val responder = object : PingResponder() {
                    override fun onMessage(stream: Stream, msg: ByteBuf) {
                        received.add(Unit)
                    }
                }
                stream.pushHandler(responder)
                return CompletableFuture.completedFuture(responder)
            }
        }
        val protocol = PingProtocol().apply { pingTimeout = Duration.ofDays(1) }
        val server = defaultQueueHost(PingBinding(serverProtocol), true)
        val client = defaultQueueHost(PingBinding(protocol), false)
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            fun open() = client.newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"),
                server.peerId,
                *server.listenAddresses().toTypedArray()
            )
            val first = open()
            val sibling = open()
            val stream = first.stream.get(10, TimeUnit.SECONDS)
            val controller = first.controller.get(10, TimeUnit.SECONDS)
            sibling.controller.get(10, TimeUnit.SECONDS)
            val scheduler = protocol.timeoutScheduler as ScheduledThreadPoolExecutor
            // Acknowledge receipt before the next request so the raw byte protocol cannot coalesce payloads.
            val pending = (0 until 64).map {
                controller.ping().also { assertThat(received.poll(10, TimeUnit.SECONDS)).isNotNull() }
            }
            assertThat(scheduler.queue).hasSize(64)
            stream.close().get(10, TimeUnit.SECONDS)
            val channel = (stream as io.libp2p.transport.implementation.P2PChannelOverNetty).nettyChannel
            channel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
            pending.forEach { request ->
                val failure = assertThrows(ExecutionException::class.java) { request.get(10, TimeUnit.SECONDS) }
                assertThat(failure.cause).isExactlyInstanceOf(ConnectionClosedException::class.java)
            }
            assertThat(scheduler.isShutdown).isFalse()
            assertThat(scheduler.queue).`as`("A closed initiator must return its 64 pending default timeout tasks")
                .isEmpty()
            assertThat(sibling.stream.get(10, TimeUnit.SECONDS).closeFuture().isDone).isFalse()
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}

private fun defaultQueueHost(binding: PingBinding, listen: Boolean): Host = host {
    transports { add(::TcpTransport) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { +StreamMuxerProtocol.getYamux() }
    if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
    protocols { +binding }
}
