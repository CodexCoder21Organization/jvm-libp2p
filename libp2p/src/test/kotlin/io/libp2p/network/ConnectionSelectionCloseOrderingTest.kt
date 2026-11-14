package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class ConnectionSelectionCloseOrderingTest {
    @Test
    fun `cleanup waits for public notification after physical close`() {
        val a = host {
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
        }
        val b = host {
            network { listen("/ip4/127.0.0.1/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
        }
        val remote = CompletableFuture<Connection>()
        b.addConnectionHandler(ConnectionHandler.create { remote.complete(it) })
        val closeNotification = CompletableFuture<Unit>()
        val releaseNotification = CountDownLatch(1)
        try {
            a.start().get(10, SECONDS)
            b.start().get(10, SECONDS)
            val parent = a.network.connect(
                b.peerId,
                ChannelVisitor { candidate ->
                    (candidate as ConnectionOverNetty).nettyChannel.closeFuture().addListener {
                        closeNotification.complete(Unit)
                        check(releaseNotification.await(10, SECONDS))
                    }
                },
                b.listenAddresses().single()
            ).get(10, SECONDS)
            remote.get(10, SECONDS).close().get(10, SECONDS)
            closeNotification.get(10, SECONDS)
            assertThat(parent.closeFuture().isDone).isFalse()
            parent.close().get(10, SECONDS)
            assertThat(a.network.connections).containsExactly(parent)
            releaseNotification.countDown()
            parent.closeFuture().get(10, SECONDS)
            (parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS)
            assertThat(a.network.connections).isEmpty()
        } finally {
            releaseNotification.countDown()
            try {
                a.stop().get(10, SECONDS)
            } finally {
                b.stop().get(10, SECONDS)
            }
        }
    }
}
