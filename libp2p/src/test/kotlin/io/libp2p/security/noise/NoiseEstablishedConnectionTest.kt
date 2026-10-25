package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.security.SecureChannel
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.SingleThreadEventExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class NoiseEstablishedConnectionTest {
    @RepeatedTest(20)
    fun `established reply progresses while one incoming handshake computation is held`() {
        runEstablishedConnectionHarness("reply")
    }
}

class NoiseEstablishedConnectionLifecycleTest {
    @Test
    fun `a connection using another secure protocol stays responsive during Noise setup`() {
        runEstablishedConnectionHarness("mixed")
    }

    @Test
    fun `closing a pending connection completes while its computation is held`() {
        runEstablishedConnectionHarness("close")
    }

    @Test
    fun `closing the last established connection restores the isolated inline path`() {
        runEstablishedConnectionHarness("closed")
    }
}

private fun runEstablishedConnectionHarness(mode: String) {
    val child = ProcessBuilder(
        System.getProperty("java.home") + "/bin/java",
        "-Dio.netty.eventLoopThreads=1",
        "-cp",
        System.getProperty("java.class.path"),
        "io.libp2p.security.noise.NoiseEstablishedConnectionHarness",
        mode
    ).redirectErrorStream(true).start()
    val output = CompletableFuture.supplyAsync { child.inputStream.bufferedReader().use { it.readText() } }
    try {
        assertTrue(child.waitFor(30, TimeUnit.SECONDS), "The controlled incoming-handshake child must finish")
        val text = output.get(5, TimeUnit.SECONDS)
        print(text)
        assertEquals(0, child.exitValue(), text)
    } finally {
        if (child.isAlive) {
            child.destroyForcibly()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "The controlled child must stop during cleanup")
        }
    }
}

object NoiseEstablishedConnectionHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.single()
        val hosts = ArrayList<Host>()
        val armed = AtomicBoolean()
        val heldOnce = AtomicBoolean()
        val gateReady = CompletableFuture<EstablishedHandshakeGate>()
        val observed = CompletableFuture<NoiseHandshakeComputation>()
        val release = CountDownLatch(1)
        val computationEntered = AtomicLong()
        val computationExited = AtomicLong()
        val computationDone = CompletableFuture<Unit>()
        val heldSession = CompletableFuture<CompletableFuture<SecureChannel.Session>>()
        val computationThread = AtomicReference<Thread>()
        val loopThread = AtomicReference<Thread>()
        var remote: Process? = null
        try {
            val server = host {
                identity { random(KeyType.ED25519) }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                protocols { +Ping() }
                secureChannels {
                    add { key, _ ->
                        val noise = NoiseXXSecureChannel(key) { snapshot ->
                            if (armed.get() && heldOnce.compareAndSet(false, true)) {
                                computationThread.set(Thread.currentThread())
                                computationEntered.set(System.nanoTime())
                                observed.complete(snapshot)
                                try {
                                    check(release.await(5, TimeUnit.SECONDS)) { "Held computation was not released within 5 seconds" }
                                } finally {
                                    computationExited.set(System.nanoTime())
                                    computationDone.complete(Unit)
                                }
                            }
                        }
                        object : SecureChannel {
                            override val protocolDescriptor = noise.protocolDescriptor
                            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
                                if (armed.get()) {
                                    val gate = EstablishedHandshakeGate()
                                    ch.pushHandler(gate)
                                    gateReady.complete(gate)
                                    val session = noise.initChannel(ch, selectedProtocol)
                                    heldSession.complete(session)
                                    return session
                                }
                                return noise.initChannel(ch, selectedProtocol)
                            }
                        }
                    }
                    if (mode == "mixed") {
                        add { key, _ -> PlaintextInsecureChannel(key) }
                    }
                }
            }.also { hosts.add(it) }
            val caller = host {
                identity { random(KeyType.ED25519) }
                protocols { +Ping() }
                if (mode == "mixed") {
                    secureChannels { add { key, _ -> PlaintextInsecureChannel(key) } }
                }
            }.also { hosts.add(it) }
            CompletableFuture.allOf(*hosts.map { it.start() }.toTypedArray()).get(5, TimeUnit.SECONDS)
            val primary = caller.newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"),
                server.peerId,
                server.listenAddresses().single()
            )
            val ping = primary.controller.get(5, TimeUnit.SECONDS)
            ping.ping().get(5, TimeUnit.SECONDS)
            val established = primary.stream.get(5, TimeUnit.SECONDS)
            val serverEstablished = server.network.connections.single() as ConnectionOverNetty
            if (mode == "closed") {
                established.connection.close().get(5, TimeUnit.SECONDS)
                serverEstablished.closeFuture().get(5, TimeUnit.SECONDS)
            }
            armed.set(true)
            remote = ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-Dio.netty.eventLoopThreads=1",
                "-cp",
                System.getProperty("java.class.path"),
                "io.libp2p.security.noise.NoiseEstablishedConnectionPeer",
                server.peerId.toBase58(),
                server.listenAddresses().single().toString()
            ).redirectErrorStream(true).start()
            val peerProcess = remote
            val remoteOutput = CompletableFuture.supplyAsync { peerProcess.inputStream.bufferedReader().use { it.readText() } }
            val gate = gateReady.get(5, TimeUnit.SECONDS)
            gate.frameReady.get(5, TimeUnit.SECONDS)
            val loop = gate.context.channel().eventLoop()
            assertTrue((established.connection as ConnectionOverNetty).nettyChannel.eventLoop() === loop, "The established RPC and incoming handshake must share the same network loop")
            val channelCount = CompletableFuture<Int>()
            loop.execute {
                try {
                    loopThread.set(Thread.currentThread())
                    assertEquals(0, (loop as SingleThreadEventExecutor).pendingTasks(), "The controlled loop must have no queued tasks before release")
                    val physicalChannels = listOf(
                        (established.connection as ConnectionOverNetty).nettyChannel,
                        serverEstablished.nettyChannel,
                        gate.context.channel()
                    )
                    assertEquals(3, physicalChannels.toSet().size, "The fixture must own three distinct physical connections")
                    assertTrue(physicalChannels.all { it.eventLoop() === loop }, "All three physical connections must share the controlled loop")
                    channelCount.complete(physicalChannels.count { it.isActive })
                    gate.release()
                } catch (failure: Throwable) {
                    // Report the loop-side assertion message on the test thread instead of a timeout.
                    channelCount.completeExceptionally(failure)
                }
            }
            val expectedChannels = if (mode == "closed") 1 else 3
            assertEquals(expectedChannels, awaitLoopResult(channelCount), "The loop must own the exact expected live physical connections")
            val snapshot = observed.get(5, TimeUnit.SECONDS)
            assertEquals(1, snapshot.pendingHandshakesOnLoop, "Only the remote peer's incoming handshake may be pending on this loop")
            assertEquals(gate.context.channel().id().asLongText(), snapshot.channelId, "The held computation must belong to the new incoming peer")
            assertEquals(mode == "closed", established.connection.closeFuture().isDone, "The original connection must retain its expected close state")
            println("FX39_ESTABLISHED_BEGIN pending=${snapshot.pendingHandshakesOnLoop} channels=${channelCount.join()} onLoop=${snapshot.onEventLoop}")
            assertEquals(loopThread.get() === computationThread.get(), snapshot.onEventLoop, "The observer thread must match the reported loop ownership")
            when (mode) {
                "reply", "mixed" -> {
                    val sentAt = System.nanoTime()
                    val reply = ping.ping()
                    var deadlineFailure: TimeoutException? = null
                    try {
                        reply.get(1, TimeUnit.SECONDS)
                    } catch (failure: TimeoutException) {
                        deadlineFailure = failure
                    }
                    val releasedAt = System.nanoTime()
                    release.countDown()
                    reply.get(5, TimeUnit.SECONDS)
                    val repliedAt = System.nanoTime()
                    println("FX39_TIMING loopThread=${loopThread.get().name} computationThread=${computationThread.get().name} loopIdentity=${System.identityHashCode(loop)} entered=${computationEntered.get()} exited=${computationExited.get()} sent=$sentAt release=$releasedAt reply=$repliedAt deadlineFailed=${deadlineFailure != null}")
                    if (deadlineFailure != null) throw deadlineFailure
                    assertFalse(snapshot.onEventLoop, "An established connection's loop must stay available while handshake computation is held")
                }
                "close" -> {
                    try {
                        gate.context.channel().close().get(1, TimeUnit.SECONDS)
                    } finally {
                        release.countDown()
                    }
                    assertFalse(snapshot.onEventLoop, "Closing a pending connection must not wait for its held computation")
                    // Check publication only after the released computation has returned and the
                    // closed channel's session has terminally failed, so a late publish cannot be missed.
                    computationDone.get(5, TimeUnit.SECONDS)
                    val sessionFailure = assertThrows<ExecutionException>("The closed incoming handshake's session must fail") {
                        heldSession.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS)
                    }
                    println("FX39_CLOSE_SESSION_FAILED ${sessionFailure.cause}")
                    loop.submit {}.get(5, TimeUnit.SECONDS)
                    assertEquals(1, server.network.connections.size, "The closed incoming handshake must not publish a connection")
                    ping.ping().get(1, TimeUnit.SECONDS)
                }
                "closed" -> {
                    assertTrue(snapshot.onEventLoop, "After both established ends close, one isolated incoming handshake must stay inline")
                    release.countDown()
                }
                else -> error("Unknown established-connection harness mode: $mode")
            }
            peerProcess.outputStream.close()
            assertTrue(peerProcess.waitFor(10, TimeUnit.SECONDS), "The incoming peer must finish after release")
            val peerText = remoteOutput.get(5, TimeUnit.SECONDS)
            if (mode == "close") {
                assertTrue(peerProcess.exitValue() != 0, "Closing the held incoming handshake must fail the peer connect: $peerText")
            } else {
                assertEquals(0, peerProcess.exitValue(), peerText)
            }
            println("FX39_ESTABLISHED_PASS")
        } finally {
            release.countDown()
            remote?.let {
                it.outputStream.close()
                if (it.isAlive) {
                    it.destroyForcibly()
                    assertTrue(it.waitFor(5, TimeUnit.SECONDS), "The incoming peer must stop during cleanup")
                }
            }
            CompletableFuture.allOf(*hosts.asReversed().map { it.stop() }.toTypedArray()).get(5, TimeUnit.SECONDS)
        }
    }
}

private fun <T> awaitLoopResult(result: CompletableFuture<T>): T =
    try {
        result.get(5, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }

object NoiseEstablishedConnectionPeer {
    @JvmStatic
    fun main(args: Array<String>) {
        val peer = host { identity { random(KeyType.ED25519) } }
        try {
            peer.start().get(5, TimeUnit.SECONDS)
            peer.network.connect(PeerId.fromBase58(args[0]), Multiaddr(args[1])).get(10, TimeUnit.SECONDS)
            System.`in`.read()
        } finally {
            peer.stop().get(5, TimeUnit.SECONDS)
        }
    }
}

private class EstablishedHandshakeGate : ChannelInboundHandlerAdapter() {
    lateinit var context: ChannelHandlerContext
    val frameReady = CompletableFuture<Unit>()
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
            frameReady.complete(Unit)
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
