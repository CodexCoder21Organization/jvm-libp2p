package io.libp2p.mux.yamux

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.StreamCapacityException
import io.libp2p.core.StreamCapacityReset
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class YamuxCapacityHostsTest {
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun testNegotiatedCapacityKeepsPingUsable(clientNew: Boolean, serverNew: Boolean) {
        val selected = AtomicReference<String>()
        fun makeHost(modern: Boolean, observe: Boolean): Host = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers {
                if (modern) {
                    +StreamMuxerProtocol { multistream, protocols ->
                        YamuxCapacityStreamMuxer(multistream.createMultistream(protocols).toStreamHandler(), multistream).also {
                            if (observe) it.muxFramesDebugHandler = ChannelVisitor { selected.set("/urlprotocol/yamux-capacity/1.0.0") }
                        }
                    }
                }
                +StreamMuxerProtocol { multistream, protocols ->
                    YamuxStreamMuxer(
                        multistream.createMultistream(protocols).toStreamHandler(), multistream,
                        DEFAULT_MAX_BUFFERED_CONNECTION_WRITES, DEFAULT_ACK_BACKLOG_LIMIT
                    ).also {
                        if (observe) it.muxFramesDebugHandler = ChannelVisitor { selected.set("/yamux/1.0.0") }
                    }
                }
            }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { +Ping() }
        }
        val client = makeHost(clientNew, true)
        val server = makeHost(serverNew, false)
        val admissions = AtomicInteger()
        server.addStreamVisitor(ChannelVisitor { stream ->
            if (!stream.isInitiator && admissions.incrementAndGet() > 1) {
                val capacity = stream as? StreamCapacityReset
                if (capacity == null) stream.reset() else capacity.resetForCapacity()
            }
        })
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS)
            val sibling = connection.muxerSession().createStream(Ping()).controller.get(5, TimeUnit.SECONDS)
            sibling.ping().get(5, TimeUnit.SECONDS)
            val next = connection.muxerSession().createStream(Ping())
            val cause = try {
                next.controller.get(5, TimeUnit.SECONDS)
                error("Expected the second stream to report receiver capacity")
            } catch (result: ExecutionException) {
                result.cause!!
            }
            if (clientNew && serverNew) {
                assertThat(cause).isInstanceOf(StreamCapacityException::class.java)
                assertThat(selected.get()).isEqualTo("/urlprotocol/yamux-capacity/1.0.0")
            } else {
                assertThat(cause).isInstanceOf(ConnectionClosedException::class.java)
                assertThat(selected.get()).isEqualTo("/yamux/1.0.0")
            }
            sibling.ping().get(5, TimeUnit.SECONDS)
            assertThat(connection.closeFuture().isDone).isFalse()
            assertThat(client.network.connections).hasSize(1)
            assertThat(admissions.get()).isEqualTo(2)
        } finally {
            client.stop().get(5, TimeUnit.SECONDS)
            server.stop().get(5, TimeUnit.SECONDS)
        }
    }
}
