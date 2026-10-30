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
                    listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray()
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
}

private fun defaultQueueHost(binding: PingBinding, listen: Boolean): Host = host {
    transports { add(::TcpTransport) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { +StreamMuxerProtocol.getYamux() }
    if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
    protocols { +binding }
}
