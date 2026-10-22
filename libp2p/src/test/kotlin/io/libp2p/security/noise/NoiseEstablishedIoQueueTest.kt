package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.protocol.Ping
import io.libp2p.transport.implementation.ConnectionOverNetty
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

class NoiseEstablishedIoQueueTest {
    @Test
    fun `established reads run before the next queued callback batch`() {
        val process = ProcessBuilder(
            java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dio.netty.eventLoopThreads=1",
            "-Dio.netty.eventLoop.maxTaskProcessingQuantumMs=1000",
            "-cp",
            System.getProperty("java.class.path"),
            NoiseEstablishedIoQueueHarness::class.java.name
        ).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The established-I/O child must finish within 20 seconds")
            val text = output.get(5, TimeUnit.SECONDS)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "The established-I/O child must close after its deadline")
            }
        }
    }
}

object NoiseEstablishedIoQueueHarness {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val hosts = mutableListOf<Host>()
        val recordReads = AtomicBoolean()
        val readObserved = AtomicBoolean()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackFinished = CompletableFuture<Unit>()
        try {
            val relay = host {
                identity { random(KeyType.ED25519) }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                protocols { +Ping() }
            }.also { hosts.add(it) }
            relay.addStreamVisitor { stream ->
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        if (recordReads.get()) readObserved.set(true)
                        ctx.fireChannelRead(msg)
                    }
                })
            }
            val monitor = host {
                identity { random(KeyType.ED25519) }
                protocols { +Ping() }
            }.also { hosts.add(it) }
            CompletableFuture.allOf(*hosts.map { it.start() }.toTypedArray()).get(5, TimeUnit.SECONDS)
            val controller = Ping().dial(monitor, relay.peerId, relay.listenAddresses().single())
                .controller.get(5, TimeUnit.SECONDS)
            controller.ping().get(5, TimeUnit.SECONDS)
            val loop = (monitor.network.connections.single() as ConnectionOverNetty).nettyChannel.eventLoop()
            assertTrue(loop === (relay.network.connections.single() as ConnectionOverNetty).nettyChannel.eventLoop())
            val pings = mutableListOf<CompletableFuture<Long>>()
            loop.execute {
                try {
                    recordReads.set(true)
                    pings.add(controller.ping())
                    callbackEntered.countDown()
                    check(releaseCallback.await(5, TimeUnit.SECONDS)) { "The test callback was not released within 5 seconds" }
                    callbackFinished.complete(Unit)
                } catch (failure: Throwable) {
                    callbackFinished.completeExceptionally(failure)
                }
            }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS), "The established ping must be written before queuing more calls")
            repeat(64) { loop.execute { pings.add(controller.ping()) } }
            val readBeforeNextBatch = CompletableFuture<Boolean>()
            loop.execute { readBeforeNextBatch.complete(readObserved.get()) }
            // Hold one callback across the 100ms transport quantum, below Netty's 1000ms default.
            // The queued calls are real pings; the assertion checks ordering rather than machine speed.
            assertFalse(releaseCallback.await(200, TimeUnit.MILLISECONDS))
            releaseCallback.countDown()
            callbackFinished.get(5, TimeUnit.SECONDS)
            assertTrue(
                readBeforeNextBatch.get(5, TimeUnit.SECONDS),
                "An established TCP read must run before the next queued callback batch after the transport task quantum"
            )
            CompletableFuture.allOf(*pings.toTypedArray()).get(5, TimeUnit.SECONDS)
            println("Established TCP reads ran before the next queued callback batch; ${pings.size} pings completed")
        } finally {
            releaseCallback.countDown()
            CompletableFuture.allOf(*hosts.asReversed().map { it.stop() }.toTypedArray()).get(5, TimeUnit.SECONDS)
        }
    }
}
