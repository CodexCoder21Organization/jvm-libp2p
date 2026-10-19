package io.libp2p.security.noise

import io.libp2p.core.Connection
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.security.SecureChannel
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NoiseQueuedReadyCallbackTest {
    @Test
    fun `queued ready callback runs before another connection computation finishes`() {
        val process = ProcessBuilder(
            java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dio.netty.eventLoopThreads=4",
            "-cp", System.getProperty("java.class.path"),
            NoiseQueuedReadyCallbackHarness::class.java.name
        ).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The queued-ready child must terminate")
            val text = output.get(5, TimeUnit.SECONDS)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}

object NoiseQueuedReadyCallbackHarness {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val hosts = mutableListOf<Host>()
        val readyConnection = AtomicReference<CompletableFuture<Connection>>()
        val releaseComputation = CountDownLatch(1)
        val computationFinished = AtomicBoolean()
        val observed = CompletableFuture<NoiseHandshakeComputation>()
        val queuedReadyOrder = CompletableFuture<Boolean>()
        val queued = AtomicBoolean()
        val targetChannel = AtomicReference<String>()
        try {
            val relay = host {
                identity { random(KeyType.ED25519) }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                secureChannels {
                    add { key, _ ->
                        val noise = NoiseXXSecureChannel(key) { observation ->
                            if (observation.channelId == targetChannel.get() && observed.complete(observation)) {
                                try {
                                    releaseComputation.await(5, TimeUnit.SECONDS)
                                } finally {
                                    computationFinished.set(true)
                                }
                            }
                        }
                        object : SecureChannel {
                            override val protocolDescriptor = noise.protocolDescriptor
                            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
                                val connection = readyConnection.get()
                                if (connection != null) {
                                    targetChannel.set((ch as P2PChannelOverNetty).nettyChannel.id().asLongText())
                                    ch.pushHandler(object : ChannelInboundHandlerAdapter() {
                                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                            if (queued.compareAndSet(false, true)) {
                                                connection.thenAcceptAsync(
                                                    {
                                                        queuedReadyOrder.complete(!computationFinished.get())
                                                        releaseComputation.countDown()
                                                    },
                                                    ctx.executor()
                                                )
                                            }
                                            ctx.fireChannelRead(msg)
                                        }
                                    })
                                }
                                return noise.initChannel(ch, selectedProtocol)
                            }
                        }
                    }
                }
            }.also { hosts.add(it) }
            val clients = List(2) { host { identity { random(KeyType.ED25519) } }.also { hosts.add(it) } }
            CompletableFuture.allOf(*hosts.map { it.start() }.toTypedArray()).get(5, TimeUnit.SECONDS)
            val ready = clients[0].network.connect(relay.peerId, relay.listenAddresses().single()).get(5, TimeUnit.SECONDS)
            readyConnection.set(CompletableFuture.completedFuture(ready))
            val next = clients[1].network.connect(relay.peerId, relay.listenAddresses().single())
            val order = queuedReadyOrder.get(10, TimeUnit.SECONDS)
            val observation = observed.get(5, TimeUnit.SECONDS)
            println("Queued ready order: pending=${observation.pendingHandshakesOnLoop}, onLoop=${observation.onEventLoop}, readyBeforeComputationFinished=$order")
            assertTrue(order, "The queued ready callback must run before the new computation finishes")
            assertEquals(1, observation.pendingHandshakesOnLoop, "This proof must exercise queued work without a second handshake on this loop")
            assertFalse(observation.onEventLoop, "Queued connection-ready work must exclude inline computation")
            next.get(5, TimeUnit.SECONDS)
        } finally {
            releaseComputation.countDown()
            CompletableFuture.allOf(*hosts.asReversed().map { it.stop() }.toTypedArray()).get(5, TimeUnit.SECONDS)
        }
    }
}
