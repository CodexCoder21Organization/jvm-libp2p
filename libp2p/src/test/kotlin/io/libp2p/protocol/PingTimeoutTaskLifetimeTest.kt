package io.libp2p.protocol

import io.libp2p.core.Host
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PingTimeoutTaskLifetimeTest {
    @Test
    fun completedPingReturnsItsTimeoutTaskWhileTheStreamRemainsOpen() {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val protocol = PingProtocol().apply {
            timeoutScheduler = scheduler
            // Keep the timeout pending independently of host speed; the reply must cancel it.
            pingTimeout = Duration.ofDays(1)
        }
        fun endpoint(binding: PingBinding, listen: Boolean): Host = host {
            transports { add(::TcpTransport) }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { +StreamMuxerProtocol.getYamux() }
            if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { +binding }
        }
        val server = endpoint(Ping(), true)
        val client = endpoint(PingBinding(protocol), false)
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val opened = client.newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"),
                server.peerId,
                *server.listenAddresses().toTypedArray()
            )
            val stream = opened.stream.get(10, TimeUnit.SECONDS)
            opened.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS)
            assertThat(stream.closeFuture().isDone).isFalse()
            assertThat(scheduler.queue).`as`("A completed ping must return its scheduled timeout task").isEmpty()
            stream.close().get(10, TimeUnit.SECONDS)
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
