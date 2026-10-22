package io.libp2p.pubsub.gossip

import io.libp2p.core.Host
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.util.netty.LoggingHandlerShort
import io.libp2p.mux.mplex.DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.handler.logging.LogLevel
import io.netty.util.ResourceLeakDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GossipLargeConcurrentTest {
    @Test
    fun `concurrent large publishes complete and arrive intact`() {
        val previousLeakLevel = ResourceLeakDetector.getLevel()
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
        val executors = List(2) { Executors.newSingleThreadScheduledExecutor() }
        val callers = Executors.newFixedThreadPool(8)
        val hosts = mutableListOf<Host>()
        val received = ConcurrentLinkedQueue<MessageApi>()
        try {
            val routers = executors.map { executor ->
                GossipRouterBuilder(
                    params = GossipParams(
                        maxGossipMessageSize = DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH * 2,
                        // Eight payloads exceed the default four-MiB pending queue.
                        slowPeerPendingBytesThreshold = DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH * 16
                    ),
                    scheduledAsyncExecutor = executor
                ).build()
            }
            val gossip = routers.map { Gossip(it, debugGossipHandler = LoggingHandlerShort(LogLevel.INFO)) }
            gossip.forEach { binding ->
                hosts += host {
                    identity { random() }
                    transports { add(::TcpTransport) }
                    network { listen("/ip4/127.0.0.1/tcp/0") }
                    secureChannels { add(::NoiseXXSecureChannel) }
                    muxers { +StreamMuxerProtocol.Mplex }
                    protocols { +binding }
                    debug { muxFramesHandler.addNettyHandler(LoggingHandlerShort(LogLevel.INFO)) }
                }
            }
            hosts.forEach { it.start().get(5, TimeUnit.SECONDS) }
            hosts[0].network.connect(hosts[1].peerId, *hosts[1].listenAddresses().toTypedArray()).get(10, TimeUnit.SECONDS)
            val topic = Topic("large-concurrent")
            gossip[1].subscribe(Subscriber { received.add(it) }, topic)
            val subscriptionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var subscribed = false
            while (!subscribed && System.nanoTime() < subscriptionDeadline) {
                subscribed = routers[0].getPeerTopics().get(1, TimeUnit.SECONDS).values.flatten().contains(topic.topic) &&
                    routers.all { router ->
                        router.submitOnEventThread { router.activePeers.isNotEmpty() }.get(1, TimeUnit.SECONDS)
                    }
                if (!subscribed) Thread.sleep(10)
            }
            assertThat(subscribed).describedAs("both gossip streams must be active and the remote subscription must reach the publisher").isTrue()
            val writes = (0 until 8).map { index ->
                CompletableFuture.supplyAsync({
                    val bytes = ByteArray(DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH + 10) { index.toByte() }
                    val buffer = bytes.toByteBuf()
                    try {
                        gossip[0].createPublisher(null, index.toLong()).publish(buffer, topic)
                    } finally {
                        buffer.release()
                    }
                }, callers).thenCompose { it }
            }
            val result = CompletableFuture.allOf(*writes.toTypedArray())
            try {
                result.get(10, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                assertThat(writes.count { it.isDone }).describedAs("all 8 large writes must complete").isEqualTo(8)
                throw e
            }
            val deliveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (received.size < 8 && System.nanoTime() < deliveryDeadline) Thread.sleep(10)
            assertThat(received).hasSize(8)
            assertThat(received).allMatch { message ->
                val bytes = message.data.toByteArray()
                bytes.size == DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH + 10 && bytes.all { it == bytes.first() }
            }
            assertThat(received.map { it.data.toByteArray().first().toInt() }.toSet()).hasSize(8)
        } finally {
            try {
                CompletableFuture.allOf(*hosts.map { it.stop() }.toTypedArray()).get(5, TimeUnit.SECONDS)
            } finally {
                try {
                    callers.shutdownNow()
                    executors.forEach { it.shutdownNow() }
                    assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
                    executors.forEach { assertThat(it.awaitTermination(5, TimeUnit.SECONDS)).isTrue() }
                    received.forEach { it.data.release() }
                } finally {
                    ResourceLeakDetector.setLevel(previousLeakLevel)
                }
            }
        }
    }
}
