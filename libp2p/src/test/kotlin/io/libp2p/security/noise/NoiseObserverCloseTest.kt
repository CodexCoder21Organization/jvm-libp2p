package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.security.SecureChannel
import io.libp2p.transport.implementation.P2PChannelOverNetty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NoiseObserverCloseTest {
    @Test
    fun `closing from a computation observer fails that connect and leaves a fresh dial usable`() {
        val hosts = mutableListOf<Host>()
        val channels = ConcurrentHashMap<String, P2PChannel>()
        val observations = CopyOnWriteArrayList<NoiseHandshakeComputation>()
        val closedChannel = AtomicReference<String>()
        val closed = CompletableFuture<Unit>()
        try {
            val relay = host {
                identity { random(KeyType.ED25519) }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                secureChannels {
                    add { key, _ ->
                        val noise = NoiseXXSecureChannel(key) { observation ->
                            observations.add(observation)
                            if (closedChannel.compareAndSet(null, observation.channelId)) {
                                channels.getValue(observation.channelId).close().whenComplete { _, failure ->
                                    if (failure == null) closed.complete(Unit) else closed.completeExceptionally(failure)
                                }
                            }
                        }
                        object : SecureChannel {
                            override val protocolDescriptor = noise.protocolDescriptor
                            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
                                channels[(ch as P2PChannelOverNetty).nettyChannel.id().asLongText()] = ch
                                return noise.initChannel(ch, selectedProtocol)
                            }
                        }
                    }
                }
            }.also { hosts.add(it) }
            val client = host { identity { random(KeyType.ED25519) } }.also { hosts.add(it) }
            CompletableFuture.allOf(*hosts.map { it.start() }.toTypedArray()).get(5, TimeUnit.SECONDS)
            assertThrows(ExecutionException::class.java) {
                client.network.connect(relay.peerId, relay.listenAddresses().single()).get(5, TimeUnit.SECONDS)
            }
            closed.get(5, TimeUnit.SECONDS)
            assertTrue(relay.network.connections.isEmpty(), "The observer-closed handshake must not publish a connection")
            val fresh = client.network.connect(relay.peerId, relay.listenAddresses().single()).get(5, TimeUnit.SECONDS)
            assertEquals(relay.peerId, fresh.secureSession().remoteId)
            assertEquals(1, observations.count { it.channelId == closedChannel.get() }, "No further computation may start on the observer-closed channel")
            assertTrue(observations.any { it.channelId != closedChannel.get() }, "The replacement must have an independent observed handshake")
            assertNotEquals(null, closedChannel.get())
        } finally {
            CompletableFuture.allOf(*hosts.asReversed().map { it.stop() }.toTypedArray()).get(5, TimeUnit.SECONDS)
        }
    }
}
