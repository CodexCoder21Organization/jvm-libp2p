package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.core.security.SecureChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NoiseReadyReentryTest {
    @RepeatedTest(10)
    fun `stress concurrent ready callback reentry`() {
        // Each child owns real TCP connections and its own one-worker loop.
        CompletableFuture.allOf(
            *Array(4) {
                CompletableFuture.runAsync {
                    runNoiseReadyReentryHarness("relay-first")
                }
            }
        ).get(25, TimeUnit.SECONDS)
    }

    @Test
    fun `ready callback retains its loop entry during synchronous reentry`() {
        runNoiseReadyReentryHarness()
    }
}

private fun runNoiseReadyReentryHarness(mode: String = "ordinary") {
    val process = ProcessBuilder(
        java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dio.netty.eventLoopThreads=1",
        "-Xmx128m",
        "-cp",
        System.getProperty("java.class.path"),
        NoiseReadyReentryHarness::class.java.name,
        mode
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

object NoiseReadyReentryHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        val hosts = mutableListOf<Host>()
        val gates = CopyOnWriteArrayList<NoiseReentryInboundGate>()
        val installed = CountDownLatch(2)
        val firstInstalled = CompletableFuture<Unit>()
        val holdInitiatorIdentity = AtomicBoolean()
        val relayReadyReturned = CountDownLatch(1)
        val secondChannel = AtomicReference<Channel>()
        val secondObservation = CompletableFuture<NoiseHandshakeComputation>()
        val reentered = CompletableFuture<Unit>()
        val firstReadyCallbacks = AtomicInteger()
        val firstConnectionReady = {
            if (firstReadyCallbacks.incrementAndGet() == 2) {
                // The earlier endpoint has returned; this last ready callback still owns its entry.
                try {
                    gates[1].release()
                    reentered.complete(Unit)
                } catch (failure: Throwable) {
                    reentered.completeExceptionally(failure)
                    throw failure
                }
            }
        }
        try {
            val relay = host {
                identity { random(KeyType.ED25519) }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                secureChannels {
                    add { key, _ ->
                        val noise = NoiseXXSecureChannel(key) { observation ->
                            if (observation.channelId == secondChannel.get()?.id()?.asLongText()) {
                                secondObservation.complete(observation)
                            }
                        }
                        object : SecureChannel {
                            override val protocolDescriptor = noise.protocolDescriptor
                            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
                                val gate = NoiseReentryInboundGate()
                                ch.pushHandler(gate)
                                val ready = noise.initChannel(ch, selectedProtocol)
                                gates.add(gate)
                                ready.thenRun {
                                    if (gates[0] === gate) {
                                        // Re-enter through the existing public pipeline while this ready callback is active.
                                        try {
                                            firstConnectionReady()
                                        } finally {
                                            relayReadyReturned.countDown()
                                        }
                                    }
                                }
                                firstInstalled.complete(Unit)
                                installed.countDown()
                                return ready
                            }
                        }
                    }
                }
            }.also { hosts.add(it) }
            val clients = List(2) { index ->
                val key = generateKeyPair(KeyType.ED25519).first
                val identity = if (index == 0) {
                    NoiseReentryIdentityGate(key, holdInitiatorIdentity, relayReadyReturned)
                } else {
                    key
                }
                host {
                    identity { factory = { identity } }
                    secureChannels {
                        add { localKey, _ ->
                            val noise = NoiseXXSecureChannel(localKey)
                            object : SecureChannel {
                                override val protocolDescriptor = noise.protocolDescriptor
                                override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
                                    val ready = noise.initChannel(ch, selectedProtocol)
                                    if (index == 0) ready.thenRun { firstConnectionReady() }
                                    return ready
                                }
                            }
                        }
                    }
                }.also { hosts.add(it) }
            }
            CompletableFuture.allOf(*hosts.map { it.start() }.toTypedArray()).get(5, TimeUnit.SECONDS)
            // Retain both first frames until both relay handshakes are installed.
            val first = clients[0].network.connect(relay.peerId, relay.listenAddresses().single())
            firstInstalled.get(5, TimeUnit.SECONDS)
            val second = clients[1].network.connect(relay.peerId, relay.listenAddresses().single())
            assertTrue(installed.await(5, TimeUnit.SECONDS), "Both relay handshakes must be installed")
            assertEquals(2, gates.size)
            val held = gates[0]
            val other = gates[1]
            held.frameReceived.get(5, TimeUnit.SECONDS)
            other.frameReceived.get(5, TimeUnit.SECONDS)
            val loop = held.context.channel().eventLoop()
            assertTrue(loop === other.context.channel().eventLoop(), "Both relay connections must own the same loop")
            secondChannel.set(other.context.channel())
            holdInitiatorIdentity.set(args.singleOrNull() == "relay-first")
            loop.execute { held.release() }
            reentered.get(5, TimeUnit.SECONDS)
            val observation = secondObservation.get(5, TimeUnit.SECONDS)
            println("Reentrant ready publication: pending=${observation.pendingHandshakesOnLoop}, onLoop=${observation.onEventLoop}")
            assertEquals(2, firstReadyCallbacks.get(), "Both endpoints of the first connection must have published")
            assertEquals(3, observation.pendingHandshakesOnLoop, "The first connection's last ready callback and both endpoints of the second connection must remain pending")
            assertFalse(observation.onEventLoop, "Synchronous reentry must not run computation ahead of the active ready callback")
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS)
        } finally {
            relayReadyReturned.countDown()
            CompletableFuture.allOf(*hosts.asReversed().map { it.stop() }.toTypedArray()).get(5, TimeUnit.SECONDS)
        }
    }
}

private class NoiseReentryInboundGate : ChannelInboundHandlerAdapter() {
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

private class NoiseReentryIdentityGate(
    private val delegate: PrivKey,
    private val armed: AtomicBoolean,
    private val relayReadyReturned: CountDownLatch
) : PrivKey(delegate.keyType) {
    override fun raw(): ByteArray = delegate.raw()
    override fun sign(data: ByteArray): ByteArray = delegate.sign(data)
    override fun publicKey(): PubKey {
        if (armed.compareAndSet(true, false)) {
            // Noise requests its local identity after sending message 3, before local publication.
            // Hold that real dependency call so the relay is guaranteed to publish first.
            check(relayReadyReturned.await(5, TimeUnit.SECONDS)) {
                "The first relay ready callback must return before the initiator identity lookup continues"
            }
        }
        return delegate.publicKey()
    }
}
