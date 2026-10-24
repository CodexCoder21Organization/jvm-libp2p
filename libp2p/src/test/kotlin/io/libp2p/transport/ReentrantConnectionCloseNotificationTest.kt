package io.libp2p.transport

import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.transport.implementation.ConnectionOverNetty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class ReentrantConnectionCloseNotificationTest {
    @Test
    fun reentrantCloseCompletesAfterPreviouslyRegisteredPhysicalCloseListeners() {
        val events = CopyOnWriteArrayList<String>()
        val closing = CompletableFuture<Unit>()
        val server = host {
            identity { random(KeyType.ED25519) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val client = host {
            identity { random(KeyType.ED25519) }
            debug {
                beforeSecureHandler.addHandler { channel ->
                    channel.closeFuture().thenRun {
                        events += "early listener"
                        channel.close().whenComplete { _, failure ->
                            events += "close operation"
                            if (failure == null) closing.complete(Unit) else closing.completeExceptionally(failure)
                        }
                    }
                }
            }
        }
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS) as ConnectionOverNetty
            connection.nettyChannel.eventLoop().submit {
                connection.nettyChannel.closeFuture().addListener { events += "later listener" }
            }.get(5, TimeUnit.SECONDS)
            server.stop().get(5, TimeUnit.SECONDS)
            closing.get(5, TimeUnit.SECONDS)
            connection.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(
                listOf("early listener", "later listener", "close operation"),
                events,
                "A reentrant close must finish after previously registered physical-close listeners"
            )
        } finally {
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }
}
