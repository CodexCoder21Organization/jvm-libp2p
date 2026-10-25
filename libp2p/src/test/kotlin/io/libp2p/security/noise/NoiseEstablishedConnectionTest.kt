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
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.SingleThreadEventExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NoiseEstablishedConnectionTest {
    @Test
    fun `established reply progresses while one incoming handshake computation is held`() {
        val child = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-Dio.netty.eventLoopThreads=1", "-cp", System.getProperty("java.class.path"),
            "io.libp2p.security.noise.NoiseEstablishedConnectionHarness"
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
}

object NoiseEstablishedConnectionHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        val hosts = ArrayList<Host>()
        val armed = AtomicBoolean()
        val heldOnce = AtomicBoolean()
        val gateReady = CompletableFuture<EstablishedHandshakeGate>()
        val observed = CompletableFuture<NoiseHandshakeComputation>()
        val release = CountDownLatch(1)
        var remote: Process? = null
        var remoteOutput: CompletableFuture<String>? = null
        try {
            val server = host {
                identity { random(KeyType.ED25519) }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                protocols { +Ping() }
                secureChannels {
                    add { key, _ ->
                        val noise = NoiseXXSecureChannel(key) { snapshot ->
                            if (armed.get() && heldOnce.compareAndSet(false, true)) {
                                observed.complete(snapshot)
                                check(release.await(5, TimeUnit.SECONDS)) { "Held computation was not released within 5 seconds" }
                            }
                        }
                        object : SecureChannel {
                            override val protocolDescriptor = noise.protocolDescriptor
                            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
                                if (armed.get()) {
                                    val gate = EstablishedHandshakeGate()
                                    ch.pushHandler(gate)
                                    gateReady.complete(gate)
                                }
                                return noise.initChannel(ch, selectedProtocol)
                            }
                        }
                    }
                }
            }.also { hosts.add(it) }
            val caller = host {
                identity { random(KeyType.ED25519) }
                protocols { +Ping() }
            }.also { hosts.add(it) }
            CompletableFuture.allOf(*hosts.map { it.start() }.toTypedArray()).get(5, TimeUnit.SECONDS)
            val primary = caller.newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"), server.peerId, server.listenAddresses().single()
            )
            val ping = primary.controller.get(5, TimeUnit.SECONDS)
            ping.ping().get(5, TimeUnit.SECONDS)
            val established = primary.stream.get(5, TimeUnit.SECONDS)
            armed.set(true)
            remote = ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-Dio.netty.eventLoopThreads=1", "-cp", System.getProperty("java.class.path"),
                "io.libp2p.security.noise.NoiseEstablishedConnectionPeer",
                server.peerId.toBase58(), server.listenAddresses().single().toString()
            ).redirectErrorStream(true).start()
            val peerProcess = remote
            remoteOutput = CompletableFuture.supplyAsync { peerProcess.inputStream.bufferedReader().use { it.readText() } }
            val gate = gateReady.get(5, TimeUnit.SECONDS)
            gate.frameReady.get(5, TimeUnit.SECONDS)
            val loop = gate.context.channel().eventLoop()
            loop.execute {
                assertEquals(0, (loop as SingleThreadEventExecutor).pendingTasks(), "The controlled loop must have no queued tasks before release")
                gate.release()
            }
            val snapshot = observed.get(5, TimeUnit.SECONDS)
            assertEquals(1, snapshot.pendingHandshakesOnLoop, "Only the remote peer's incoming handshake may be pending on this loop")
            assertFalse(established.connection.closeFuture().isDone, "The original connection must remain established")
            println("FX39_ESTABLISHED_BEGIN pending=${snapshot.pendingHandshakesOnLoop} onLoop=${snapshot.onEventLoop}")
            ping.ping().get(1, TimeUnit.SECONDS)
            assertFalse(snapshot.onEventLoop, "An established connection's loop must stay available while handshake computation is held")
            release.countDown()
            peerProcess.outputStream.close()
            assertTrue(peerProcess.waitFor(10, TimeUnit.SECONDS), "The incoming peer must finish after release")
            assertEquals(0, peerProcess.exitValue(), remoteOutput.get(5, TimeUnit.SECONDS))
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
    override fun handlerAdded(ctx: ChannelHandlerContext) { context = ctx }
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (released) ctx.fireChannelRead(msg) else {
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
