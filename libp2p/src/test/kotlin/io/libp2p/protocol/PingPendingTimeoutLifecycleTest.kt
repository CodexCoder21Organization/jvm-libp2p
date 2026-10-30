package io.libp2p.protocol

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.P2PChannelOverNetty
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
import java.util.concurrent.atomic.AtomicBoolean

class PingPendingTimeoutLifecycleTest {
    @Test
    fun pendingPingClosesAndExpiresWithoutKeepingTimeoutWork() {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val clientProtocol = PingProtocol().apply {
            timeoutScheduler = scheduler
            pingTimeout = Duration.ofDays(1)
        }
        val holdReplies = AtomicBoolean(false)
        val received = LinkedBlockingQueue<Unit>()
        val serverProtocol = object : PingProtocol() {
            override fun onStartResponder(stream: Stream): CompletableFuture<PingController> {
                val responder = object : PingResponder() {
                    override fun onMessage(stream: Stream, msg: ByteBuf) {
                        if (holdReplies.get()) received.offer(Unit) else super.onMessage(stream, msg)
                    }
                }
                stream.pushHandler(responder)
                return CompletableFuture.completedFuture(responder)
            }
        }
        fun endpoint(binding: PingBinding, listen: Boolean): Host = host {
            transports { add(::TcpTransport) }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { +StreamMuxerProtocol.getYamux() }
            if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { +binding }
        }
        val server = endpoint(PingBinding(serverProtocol), true)
        val client = endpoint(PingBinding(clientProtocol), false)
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            fun open(): Pair<Stream, PingController> {
                val promise = client.newStream<PingController>(
                    listOf("/ipfs/ping/1.0.0"),
                    server.peerId,
                    *server.listenAddresses().toTypedArray()
                )
                return promise.stream.get(10, TimeUnit.SECONDS) to promise.controller.get(10, TimeUnit.SECONDS)
            }
            val (first, firstController) = open()
            val (sibling, siblingController) = open()
            firstController.ping().get(10, TimeUnit.SECONDS)
            siblingController.ping().get(10, TimeUnit.SECONDS)
            assertThat(scheduler.queue).isEmpty()
            holdReplies.set(true)
            val pending = firstController.ping()
            assertThat(received.poll(10, TimeUnit.SECONDS)).isNotNull()
            assertThat(scheduler.queue).hasSize(1)
            first.close().get(10, TimeUnit.SECONDS)
            val closeSettled = CompletableFuture<Unit>()
            (first as P2PChannelOverNetty).nettyChannel.eventLoop().execute { closeSettled.complete(Unit) }
            closeSettled.get(10, TimeUnit.SECONDS)
            val closed = assertThrows(ExecutionException::class.java) { pending.get(10, TimeUnit.SECONDS) }
            assertThat(closed.cause).isExactlyInstanceOf(ConnectionClosedException::class.java)
            assertThat(scheduler.isShutdown).isFalse()
            assertThat(scheduler.queue).`as`("Closing one initiator must cancel its outstanding timeout").isEmpty()
            holdReplies.set(false)
            siblingController.ping().get(10, TimeUnit.SECONDS)
            holdReplies.set(true)
            clientProtocol.pingTimeout = Duration.ZERO
            val expired = siblingController.ping()
            val timeout = assertThrows(ExecutionException::class.java) { expired.get(10, TimeUnit.SECONDS) }
            assertThat(timeout.cause).isExactlyInstanceOf(PingTimeoutException::class.java)
            assertThat(received.poll(10, TimeUnit.SECONDS)).isNotNull()
            assertThat(scheduler.queue).`as`("An expired ping must remove its timeout work").isEmpty()
            sibling.close().get(10, TimeUnit.SECONDS)
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    server.stop().get(10, TimeUnit.SECONDS)
                } finally {
                    scheduler.shutdownNow()
                    assertThat(scheduler.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
                }
            }
        }
    }
}
