package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.security.SecureChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NoiseLoopPendingHandshakeTest {
    @Test
    fun `another connection becomes ready before a held handshake computation finishes`() {
        val process = ProcessBuilder(
            java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dio.netty.eventLoopThreads=1",
            "-cp",
            System.getProperty("java.class.path"),
            NoiseLoopPendingHandshakeHarness::class.java.name
        ).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The controlled two-connection child must terminate")
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

object NoiseLoopPendingHandshakeHarness {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val hosts = mutableListOf<Host>()
        val gates = CopyOnWriteArrayList<NoiseInboundGate>()
        val installed = CountDownLatch(2)
        val releaseFirst = CountDownLatch(1)
        val firstChannel = AtomicReference<Channel>()
        val firstStarted = AtomicBoolean()
        val firstFinished = AtomicBoolean()
        val firstObservation = CompletableFuture<NoiseHandshakeComputation>()
        val secondReadyBeforeFirstFinished = CompletableFuture<Boolean>()
        try {
            val relay = host {
                identity { random(KeyType.ED25519) }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                secureChannels {
                    add { key, _ ->
                        val noise = NoiseXXSecureChannel(key) { observation ->
                            if (observation.channelId == firstChannel.get()?.id()?.asLongText() && firstStarted.compareAndSet(false, true)) {
                                firstObservation.complete(observation)
                                try {
                                    // This is the controlled computation seam. Only the test waits here.
                                    releaseFirst.await(5, TimeUnit.SECONDS)
                                } finally {
                                    firstFinished.set(true)
                                }
                            }
                        }
                        object : SecureChannel {
                            override val protocolDescriptor = noise.protocolDescriptor
                            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
                                val gate = NoiseInboundGate()
                                ch.pushHandler(gate)
                                val ready = noise.initChannel(ch, selectedProtocol)
                                gates.add(gate)
                                installed.countDown()
                                return ready
                            }
                        }
                    }
                }
            }.also { hosts.add(it) }
            val clients = List(2) {
                host { identity { random(KeyType.ED25519) } }.also { hosts.add(it) }
            }
            CompletableFuture.allOf(*hosts.map { it.start() }.toTypedArray()).get(5, TimeUnit.SECONDS)
            // Retain both first frames until both relay handshakes are installed.
            val first = clients[0].network.connect(relay.peerId, relay.listenAddresses().single())
            val second = clients[1].network.connect(relay.peerId, relay.listenAddresses().single())
            assertTrue(installed.await(5, TimeUnit.SECONDS), "Both relay handshakes must be installed")
            assertEquals(2, gates.size)
            val held = gates[0]
            val other = gates[1]
            held.frameReceived.get(5, TimeUnit.SECONDS)
            other.frameReceived.get(5, TimeUnit.SECONDS)
            val loop = held.context.channel().eventLoop()
            assertTrue(loop === other.context.channel().eventLoop(), "Both relay connections must own the same loop")
            firstChannel.set(held.context.channel())
            val secondReady = CompletableFuture<Unit>()
            relay.addConnectionHandler { connection ->
                if ((connection as io.libp2p.transport.implementation.ConnectionOverNetty).nettyChannel === other.context.channel()) {
                    secondReadyBeforeFirstFinished.complete(!firstFinished.get())
                    secondReady.complete(Unit)
                    releaseFirst.countDown()
                }
            }
            loop.execute { held.release() }
            loop.execute { other.release() }
            secondReady.get(10, TimeUnit.SECONDS)
            val observation = firstObservation.get(5, TimeUnit.SECONDS)
            println("Controlled Noise order: pending=${observation.pendingHandshakesOnLoop}, onLoop=${observation.onEventLoop}, secondReadyBeforeFirstFinished=${secondReadyBeforeFirstFinished.join()}")
            assertTrue(secondReadyBeforeFirstFinished.join(), "The second connection-ready callback must run before the first handshake computation finishes")
            assertFalse(observation.onEventLoop, "With another handshake pending on this loop, computation must use a worker")
            assertEquals(4, observation.pendingHandshakesOnLoop, "Both ends of both in-process connections share this loop")
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            CompletableFuture.allOf(*hosts.asReversed().map { it.stop() }.toTypedArray()).get(5, TimeUnit.SECONDS)
        }
    }
}

private class NoiseInboundGate : ChannelInboundHandlerAdapter() {
    lateinit var context: ChannelHandlerContext
    val frameReceived = CompletableFuture<Unit>()
    private val frames = java.util.ArrayDeque<Any>()
    private var released = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        context = ctx
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (released) {
            ctx.fireChannelRead(msg)
        } else {
            frames.addLast(msg)
            frameReceived.complete(Unit)
        }
    }

    fun release() {
        released = true
        while (frames.isNotEmpty()) context.fireChannelRead(frames.removeFirst())
        context.fireChannelReadComplete()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        while (frames.isNotEmpty()) ReferenceCountUtil.release(frames.removeFirst())
        ctx.fireChannelInactive()
    }
}
