package io.libp2p.protocol

import io.libp2p.core.Connection
import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.libp2p.transport.tcp.TcpTransport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class PingSchedulerReplacementTest {
    @Test
    fun `replacing the public timeout scheduler preserves each live root`() {
        val firstScheduler = ScheduledThreadPoolExecutor(1)
        val secondScheduler = ScheduledThreadPoolExecutor(1)
        val protocol = PingProtocol().apply { timeoutScheduler = firstScheduler }
        val firstServer = pingHost(Ping(), listen = true)
        val secondServer = pingHost(Ping(), listen = true)
        val client = pingHost(PingBinding(protocol))

        try {
            firstServer.start().get(10, TimeUnit.SECONDS)
            secondServer.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)

            val first = openPing(client, firstServer, "the first root pings on its supplied scheduler")
            assertThat(firstScheduler.isShutdown).isFalse()

            protocol.timeoutScheduler = secondScheduler
            val second = openPing(client, secondServer, "the second root pings on the replacement scheduler")
            assertThat(firstScheduler.isShutdown).isFalse()
            assertThat(secondScheduler.isShutdown).isFalse()

            first.stream.close().get(10, TimeUnit.SECONDS)
            first.stream.closeFuture().get(10, TimeUnit.SECONDS)
            awaitCloseCallbacks(first.stream)
            assertThat(firstScheduler.isShutdown).isTrue()
            assertThat(secondScheduler.isShutdown).isFalse()
            assertThatCode {
                assertThat(second.controller.ping().get(10, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(0)
            }.`as`("closing the first root leaves the second root's scheduler usable")
                .doesNotThrowAnyException()

            first.connection.close().get(10, TimeUnit.SECONDS)
            first.connection.closeFuture().get(10, TimeUnit.SECONDS)
            second.stream.close().get(10, TimeUnit.SECONDS)
            second.stream.closeFuture().get(10, TimeUnit.SECONDS)
            awaitCloseCallbacks(second.stream)
            assertThat(secondScheduler.isShutdown).isTrue()
            second.connection.close().get(10, TimeUnit.SECONDS)
            second.connection.closeFuture().get(10, TimeUnit.SECONDS)

            val reopened = openPing(
                client,
                firstServer,
                "a new root on the shared binding works after both earlier schedulers close"
            )
            assertThat(reopened.connection).isNotSameAs(first.connection)
            reopened.stream.close().get(10, TimeUnit.SECONDS)
            reopened.stream.closeFuture().get(10, TimeUnit.SECONDS)
            awaitCloseCallbacks(reopened.stream)
            reopened.connection.close().get(10, TimeUnit.SECONDS)
            reopened.connection.closeFuture().get(10, TimeUnit.SECONDS)
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    secondServer.stop().get(10, TimeUnit.SECONDS)
                } finally {
                    try {
                        firstServer.stop().get(10, TimeUnit.SECONDS)
                    } finally {
                        firstScheduler.shutdownNow()
                        secondScheduler.shutdownNow()
                        val firstTerminated = firstScheduler.awaitTermination(10, TimeUnit.SECONDS)
                        val secondTerminated = secondScheduler.awaitTermination(10, TimeUnit.SECONDS)
                        assertThat(firstTerminated).isTrue()
                        assertThat(secondTerminated).isTrue()
                    }
                }
            }
        }
    }
}

private data class OpenedPing(
    val connection: Connection,
    val stream: Stream,
    val controller: PingController
)

private fun openPing(client: Host, server: Host, description: String): OpenedPing {
    val opening = client.newStream<PingController>(
        listOf(PING_PROTOCOL),
        server.peerId,
        *server.listenAddresses().toTypedArray()
    )
    val stream = opening.stream.get(10, TimeUnit.SECONDS)
    val controller = opening.controller.get(10, TimeUnit.SECONDS)
    assertThatCode {
        assertThat(controller.ping().get(10, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(0)
    }.`as`(description).doesNotThrowAnyException()
    return OpenedPing(stream.connection, stream, controller)
}

private fun pingHost(binding: PingBinding, listen: Boolean = false): Host = host {
    if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
    transports { add(::TcpTransport) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    protocols { add(binding) }
}

private fun awaitCloseCallbacks(stream: Stream) {
    val complete = CompletableFuture<Unit>()
    (stream as P2PChannelOverNetty).nettyChannel.eventLoop().execute { complete.complete(Unit) }
    complete.get(10, TimeUnit.SECONDS)
}

private const val PING_PROTOCOL = "/ipfs/ping/1.0.0"
