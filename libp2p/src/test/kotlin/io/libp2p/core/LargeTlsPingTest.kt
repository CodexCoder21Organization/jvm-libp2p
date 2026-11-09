package io.libp2p.core

import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.security.tls.TlsSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LargeTlsPingTest {
    @RepeatedTest(10)
    fun `large replies keep the shared TLS connection usable`() {
        val size = 200 * 1024
        val client = HostBuilder()
            .transport(::TcpTransport)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .build()
        val server = HostBuilder()
            .transport(::TcpTransport)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(Ping(size))
            .listen("/ip4/127.0.0.1/tcp/0")
            .build()
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val connection = client.network.connect(server.peerId, server.listenAddresses().single())
                .get(5, TimeUnit.SECONDS)
            val controllers = (0 until 4).map {
                connection.muxerSession().createStream(Ping(size)).controller.get(5, TimeUnit.SECONDS)
            }
            val callers = controllers.map { controller ->
                CompletableFuture.runAsync {
                    // Each caller waits for its own previous reply while sibling streams run concurrently.
                    repeat(20) {
                        assertThatCode {
                    assertThat(controller.ping().get(5, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(0)
                }.doesNotThrowAnyException()
                    }
                }
            }
            CompletableFuture.allOf(*callers.toTypedArray()).get(30, TimeUnit.SECONDS)
            assertThat(connection.closeFuture().isDone).isFalse()
        } finally {
            CompletableFuture.allOf(client.stop(), server.stop()).get(5, TimeUnit.SECONDS)
        }
    }
}
