package io.libp2p.protocol

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class PingStreamLifetimeTest {
    @Test
    fun `closing one stream keeps its shared binding usable by a sibling and later roots`() {
        val sharedPingBinding = PingBinding(PingProtocol())
        val firstServer = pingHost(Ping(), listen = true)
        val secondServer = pingHost(Ping(), listen = true)
        val client = pingHost(sharedPingBinding)

        try {
            firstServer.start().get(10, TimeUnit.SECONDS)
            secondServer.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)

            val first = openPing(client, firstServer)
            val sibling = openPing(client, secondServer)

            first.stream.close().get(10, TimeUnit.SECONDS)
            first.stream.closeFuture().get(10, TimeUnit.SECONDS)
            awaitCloseCallbacks(first.stream)

            val closedCall = assertThrows(ExecutionException::class.java) {
                first.controller.ping().get(10, TimeUnit.SECONDS)
            }
            assertThat(closedCall.cause).isExactlyInstanceOf(ConnectionClosedException::class.java)
            assertThat(sibling.connection.closeFuture().isDone).isFalse()
            assertThatCode { sibling.controller.ping().get(10, TimeUnit.SECONDS) }
                .`as`("the shared Ping binding remains usable on the sibling root")
                .doesNotThrowAnyException()

            sibling.stream.close().get(10, TimeUnit.SECONDS)
            sibling.stream.closeFuture().get(10, TimeUnit.SECONDS)
            awaitCloseCallbacks(sibling.stream)
            first.connection.close().get(10, TimeUnit.SECONDS)
            first.connection.closeFuture().get(10, TimeUnit.SECONDS)
            sibling.connection.close().get(10, TimeUnit.SECONDS)
            sibling.connection.closeFuture().get(10, TimeUnit.SECONDS)

            var reopened: OpenedPing? = null
            assertThatCode { reopened = openPing(client, firstServer) }
                .`as`("the shared Ping binding remains usable after all earlier roots close")
                .doesNotThrowAnyException()
            val reopenedPing = requireNotNull(reopened)
            assertThat(reopenedPing.connection).isNotSameAs(first.connection)
            reopenedPing.stream.close().get(10, TimeUnit.SECONDS)
            reopenedPing.stream.closeFuture().get(10, TimeUnit.SECONDS)
            awaitCloseCallbacks(reopenedPing.stream)
            reopenedPing.connection.close().get(10, TimeUnit.SECONDS)
            reopenedPing.connection.closeFuture().get(10, TimeUnit.SECONDS)
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
                        (client.network.transports + secondServer.network.transports + firstServer.network.transports)
                            .forEach { transport ->
                                assertThat(transport.activeConnections).isZero()
                                assertThat(transport.activeListeners).isZero()
                            }
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

private fun openPing(client: Host, server: Host): OpenedPing {
    val opening = client.newStream<PingController>(
        listOf(PING_PROTOCOL),
        server.peerId,
        *server.listenAddresses().toTypedArray()
    )
    val stream = opening.stream.get(10, TimeUnit.SECONDS)
    val controller = opening.controller.get(10, TimeUnit.SECONDS)
    assertThat(controller.ping().get(10, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(0)
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
