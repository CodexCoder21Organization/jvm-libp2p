package io.libp2p.protocol.circuit

import io.libp2p.core.Connection
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CircuitConcurrentCloseTest {
    @Test
    fun simultaneousAndRepeatedClosesLeaveAnIndependentCircuitUsable() {
        ConcurrentCircuitFixture().use { fixture ->
            fixture.start()
            val survivor = fixture.open()
            val workers = Executors.newFixedThreadPool(16)
            try {
                repeat(8) {
                    val pair = fixture.open()
                    val ready = CountDownLatch(16)
                    val start = CompletableFuture<Unit>()
                    val closes = (0 until 16).map { caller ->
                        CompletableFuture.runAsync({
                            ready.countDown()
                            start.get(10, TimeUnit.SECONDS)
                            val root = if (caller % 2 == 0) pair.local else pair.remote
                            root.close().get(10, TimeUnit.SECONDS)
                        }, workers)
                    }
                    val allReady = ready.await(10, TimeUnit.SECONDS)
                    start.complete(Unit)
                    assertTrue(allReady, "All 16 close callers reach the shared start barrier")
                    CompletableFuture.allOf(*closes.toTypedArray()).get(10, TimeUnit.SECONDS)
                    pair.awaitClosed()
                    // Both endpoints are already closed; repeated close must still settle.
                    pair.local.close().get(10, TimeUnit.SECONDS)
                    pair.remote.close().get(10, TimeUnit.SECONDS)
                    fixture.assertPairedCloseCount(pair.relayStop)
                    fixture.assertUsable(survivor)
                }
            } finally {
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun pairedCloseFailureIsForwardedWithoutClosingAnIndependentRoot() {
        ConcurrentCircuitFixture().use { fixture ->
            fixture.start()
            val survivor = fixture.open()
            val pair = fixture.open()
            val failure = IllegalStateException("The paired relay stream rejected its close")
            val observed = CompletableFuture<Throwable>()
            val inactive = CompletableFuture<Unit>()
            val targetChannel = (pair.relayStop as P2PChannelOverNetty).nettyChannel
            val sourceChannel = (pair.relayHop as P2PChannelOverNetty).nettyChannel
            fixture.failPairedClose(pair.relayStop, failure)
            sourceChannel.eventLoop().submit {
                pair.relayHop.pushHandler(object : ChannelInboundHandlerAdapter() {
                    @Deprecated("Deprecated in Java")
                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                        observed.complete(cause)
                    }
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        inactive.complete(Unit)
                        ctx.fireChannelInactive()
                    }
                })
            }.get(10, TimeUnit.SECONDS)
            try {
                pair.local.close().get(10, TimeUnit.SECONDS)
                awaitCircuit(fixture.pairedCloseAttempt, "The proxy must attempt its paired stream close")
                awaitCircuit(inactive, "The source inactive event must continue after a paired close failure")
                assertSame(failure, awaitCircuit(observed, "A failed paired close must reach the source exception handler"))
                assertFalse(pair.relayStop.closeFuture().isDone)
                fixture.assertPairedCloseCount(pair.relayStop)
                fixture.assertUsable(survivor)
            } finally {
                targetChannel.eventLoop().submit {
                    targetChannel.close()
                }.get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun delayedPairedCloseFailureReachesTheSourcePipelineTailAfterTeardown() {
        ConcurrentCircuitFixture().use { fixture ->
            fixture.start()
            val survivor = fixture.open()
            val pair = fixture.open()
            val failure = IllegalStateException("The delayed paired relay close failed")
            val closeResult = CompletableFuture<Unit>()
            val reported = CompletableFuture<Throwable>()
            val logger = LogManager.getLogger("io.netty.channel.DefaultChannelPipeline") as Logger
            val appender = object : AbstractAppender(
                "circuit-paired-close-observer",
                null,
                PatternLayout.createDefaultLayout(),
                false,
                Property.EMPTY_ARRAY
            ) {
                override fun append(event: LogEvent) {
                    if (event.thrown === failure) reported.complete(event.thrown)
                }
            }
            appender.start()
            logger.addAppender(appender)
            try {
                fixture.overridePairedClose(pair.relayStop, closeResult)
                pair.local.close().get(10, TimeUnit.SECONDS)
                awaitCircuit(fixture.pairedCloseAttempt, "The proxy must request the held paired close")
                awaitCircuit(pair.relayHop.closeFuture(), "The source relay stream must close before the delayed failure")
                val source = (pair.relayHop as P2PChannelOverNetty).nettyChannel
                source.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
                // Application handlers have been removed; Netty's persistent tail reports late failures.
                closeResult.completeExceptionally(failure)
                assertSame(failure, awaitCircuit(reported, "The source pipeline tail must report its delayed paired-close failure"))
                fixture.assertPairedCloseCount(pair.relayStop)
                fixture.assertUsable(survivor)
            } finally {
                logger.removeAppender(appender)
                appender.stop()
                (pair.relayStop as P2PChannelOverNetty).nettyChannel.close()
            }
        }
    }
}

private class ConcurrentCircuitPair(
    val local: Connection,
    val remote: Connection,
    val child: Stream,
    val remoteChild: Stream,
    val relayHop: Stream,
    val relayStop: Stream
) {
    fun awaitClosed() {
        listOf(
            local.closeFuture(),
            remote.closeFuture(),
            child.closeFuture(),
            remoteChild.closeFuture(),
            relayHop.closeFuture(),
            relayStop.closeFuture()
        ).forEach { awaitCircuit(it, "Both circuit roots, relay streams and their children must close") }
        listOf(relayHop, relayStop).forEach { stream ->
            (stream as P2PChannelOverNetty).nettyChannel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
        }
    }
}

private class ConcurrentCircuitFixture : AutoCloseable {
    private val hosts = mutableListOf<Host>()
    private val runners = mutableListOf<ScheduledThreadPoolExecutor>()
    private val accepted = LinkedBlockingQueue<Stream>()
    private val relayStreams = ConcurrentLinkedQueue<Stream>()
    private val protocol = "/test/concurrent-circuit-close/1.0.0"
    private val pairedCloseResult = AtomicReference<Pair<Stream, CompletableFuture<Unit>>?>()
    private val pairedCloseCounts = ConcurrentHashMap<Stream, AtomicInteger>()

    fun assertPairedCloseCount(stream: Stream) {
        assertEquals(1, pairedCloseCounts[stream]?.get() ?: 0, "The proxy closes each paired STOP stream exactly once")
    }
    val pairedCloseAttempt = CompletableFuture<Unit>()

    fun failPairedClose(stream: Stream, failure: Throwable) {
        overridePairedClose(stream, CompletableFuture.failedFuture(failure))
    }

    fun overridePairedClose(stream: Stream, result: CompletableFuture<Unit>) {
        pairedCloseResult.set(stream to result)
    }
    private lateinit var relay: Host
    private lateinit var server: Host
    private lateinit var client: Host
    private lateinit var address: Multiaddr

    fun start() {
        relay = build(emptyList())
        relay.addStreamVisitor { stream -> relayStreams.add(stream) }
        relay.start().get(10, TimeUnit.SECONDS)
        address = relay.listenAddresses().first()
        val candidates = listOf(RelayTransport.CandidateRelay(relay.peerId, relay.listenAddresses()))
        server = build(candidates, "$address/p2p-circuit")
        client = build(candidates)
        server.start().get(10, TimeUnit.SECONDS)
        client.start().get(10, TimeUnit.SECONDS)
    }

    fun open(): ConcurrentCircuitPair {
        val before = relayStreams.toSet()
        val target = address.concatenated(Multiaddr("/p2p-circuit/p2p/${server.peerId.toBase58()}"))
        val root = client.network.transports.single { it is RelayTransport }
            .dial(target, client.network.connectionHandler, null).get(10, TimeUnit.SECONDS)
        val opening = client.newStream<Unit>(listOf(protocol), root)
        val child = opening.stream.get(10, TimeUnit.SECONDS)
        opening.controller.get(10, TimeUnit.SECONDS)
        val remote = requireNotNull(accepted.poll(10, TimeUnit.SECONDS)) { "The circuit must accept its child" }
        val newStreams = relayStreams.filter { it !in before }
        val hop = newStreams.single { !it.isInitiator && it.remotePeerId() == client.peerId }
        val stop = newStreams.single { it.isInitiator && it.remotePeerId() == server.peerId }
        return ConcurrentCircuitPair(root, remote.connection, child, remote, hop, stop)
    }

    fun assertUsable(pair: ConcurrentCircuitPair) {
        assertFalse(pair.local.closeFuture().isDone)
        assertFalse(pair.remote.closeFuture().isDone)
        val opening = client.newStream<Unit>(listOf(protocol), pair.local)
        val child = opening.stream.get(10, TimeUnit.SECONDS)
        opening.controller.get(10, TimeUnit.SECONDS)
        val remote = requireNotNull(accepted.poll(10, TimeUnit.SECONDS))
        assertSame(pair.remote, remote.connection)
        assertEquals(client.peerId, remote.connection.secureSession().remoteId)
        child.close().get(10, TimeUnit.SECONDS)
        remote.closeFuture().get(10, TimeUnit.SECONDS)
    }

    private fun build(candidates: List<RelayTransport.CandidateRelay>, circuit: String? = null): Host {
        val key = generateKeyPair(KeyType.ED25519).first
        val stop = object : CircuitStopProtocol.Binding(CircuitStopProtocol()) {
            override fun initChannel(
                ch: P2PChannel,
                selectedProtocol: String
            ): CompletableFuture<out CircuitStopProtocol.StopController> =
                super.initChannel(ch, selectedProtocol).thenApply { controller ->
                    object : CircuitStopProtocol.StopController by controller {
                        override fun getStream(): Stream {
                            val stream = controller.stream
                            return object : Stream by stream {
                                override fun close(): CompletableFuture<Unit> {
                                    pairedCloseCounts.computeIfAbsent(stream) { AtomicInteger() }.incrementAndGet()
                                    val injected = pairedCloseResult.get()
                                    if (injected != null && injected.first === stream) {
                                        pairedCloseAttempt.complete(Unit)
                                        return injected.second
                                    }
                                    return stream.close()
                                }
                            }
                        }
                    }
                }
        }
        val hop = CircuitHopProtocol.Binding(
            CircuitHopProtocol.RelayManager.limitTo(key, PeerId.fromPubKey(key.publicKey()), 5),
            stop
        )
        val runner = ScheduledThreadPoolExecutor(1).also { runners.add(it) }
        lateinit var relayTransport: RelayTransport
        val result = host {
            identity { factory = { key } }
            transports {
                add(::TcpTransport)
                add { upgrader -> RelayTransport(hop, stop, upgrader, { candidates }, runner).also { relayTransport = it } }
            }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { +StreamMuxerProtocol.getYamux() }
            network {
                listen("/ip4/127.0.0.1/tcp/0")
                if (circuit != null) listen(circuit)
            }
            protocols {
                +hop
                +stop
                +object : StrictProtocolBinding<Unit>(
                    protocol,
                    object : P2PChannelHandler<Unit> {
                        override fun initChannel(ch: P2PChannel): CompletableFuture<Unit> {
                            val stream = ch as Stream
                            if (!stream.isInitiator) accepted.add(stream)
                            return CompletableFuture.completedFuture(Unit)
                        }
                    }
                ) {}
            }
        }
        relayTransport.setHost(result)
        hosts.add(result)
        return result
    }

    override fun close() {
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure?.addSuppressed(caught)
            }
        }
        hosts.asReversed().forEach { host -> cleanup { host.stop().get(10, TimeUnit.SECONDS) } }
        runners.forEach { runner ->
            cleanup {
                runner.shutdownNow()
                assertTrue(runner.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
        failure?.let { throw it }
    }
}

private fun <T> awaitCircuit(future: CompletableFuture<T>, message: String): T = try {
    future.get(10, TimeUnit.SECONDS)
} catch (failure: TimeoutException) {
    throw AssertionError(message, failure)
}
