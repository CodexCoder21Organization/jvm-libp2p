package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.protocol.Ping
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NoiseHandshakeOffEventLoopTest {
    @Test
    fun `single Noise handshake runs crypto inline on event loops`() {
        val processOutput = runNoiseHandshakeHarness("inline", 10)

        assertThat(processOutput).contains("Noise handshake inline path:")
    }

    @Test
    fun `twenty sequential Noise handshakes stay inline and within the latency budget`() {
        val processOutput = runNoiseHandshakeHarness("sequential", 25)

        assertThat(processOutput).contains("Sequential Noise handshakes:")
    }

    @Test
    fun `established connection stays responsive during concurrent Noise handshakes`() {
        // PlainNettyTransport's worker group is process-wide and intentionally never
        // shut down. Run the one-worker reproduction in a child JVM so its documented
        // Netty setting and singleton cannot leak into any sibling test.
        val javaExecutable = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            javaExecutable,
            "-Dio.netty.eventLoopThreads=1",
            "-cp",
            System.getProperty("java.class.path"),
            NoiseHandshakeStarvationHarness::class.java.name
        ).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        val processOutput = output.get(5, TimeUnit.SECONDS)
        print(processOutput)

        assertThat(finished)
            .withFailMessage("Noise handshake starvation child JVM did not finish within 15 seconds:\n%s", processOutput)
            .isTrue()
        assertThat(process.exitValue())
            .withFailMessage("Noise handshake starvation child JVM failed:\n%s", processOutput)
            .isZero()
        assertThat(processOutput).contains("Noise handshake storm:")
    }

    @Test
    fun `concurrent handshakes preserve both peer identities and immediate mux negotiation`() {
        val hosts = mutableListOf<Host>()
        try {
            val relay = newNoiseHost(listen = true).also { hosts += it }
            val dialers = List(CONCURRENT_DIALERS) { newNoiseHost().also { host -> hosts += host } }
            startNoiseHosts(hosts)

            val relayAddress = relay.listenAddresses().single()
            val outboundConnections = dialers.map {
                it.network.connect(relay.peerId, relayAddress)
            }
            CompletableFuture.allOf(*outboundConnections.toTypedArray()).get(20, TimeUnit.SECONDS)

            outboundConnections.forEachIndexed { index, connectionFuture ->
                val session = connectionFuture.join().secureSession()
                assertThat(session.localId).isEqualTo(dialers[index].peerId)
                assertThat(session.remoteId).isEqualTo(relay.peerId)
            }

            assertThat(relay.network.connections).hasSize(CONCURRENT_DIALERS)
            assertThat(relay.network.connections.map { it.secureSession().localId }.toSet())
                .containsExactly(relay.peerId)
            assertThat(relay.network.connections.map { it.secureSession().remoteId }.toSet())
                .containsExactlyInAnyOrderElementsOf(dialers.map { it.peerId })
        } finally {
            stopNoiseHosts(hosts)
        }
    }

    @Test
    fun `corrupted handshake frame fails promptly and closes the channel`() {
        val hosts = mutableListOf<Host>()
        val rawChannel = CompletableFuture<P2PChannel>()
        val corrupter = CorruptFinalNoiseFrame()
        try {
            val relay = newNoiseHost(listen = true).also { hosts += it }
            val dialer = host {
                identity {
                    random(KeyType.ED25519)
                }
                debug {
                    beforeSecureHandler.addHandler {
                        rawChannel.complete(it)
                        it.pushHandler(corrupter)
                    }
                }
            }.also { hosts += it }
            startNoiseHosts(hosts)

            val connection = dialer.network.connect(relay.peerId, relay.listenAddresses().single())

            val startedAt = System.nanoTime()
            val failure = catchThrowable { connection.get(5, TimeUnit.SECONDS) }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertThat(corrupter.corrupted.get()).isTrue()
            assertThat(failure).isInstanceOf(ExecutionException::class.java)
            assertThat(elapsedMillis).isLessThan(2_000)
            rawChannel.get(5, TimeUnit.SECONDS).closeFuture().get(5, TimeUnit.SECONDS)
        } finally {
            stopNoiseHosts(hosts)
        }
    }

    private class CorruptFinalNoiseFrame : ChannelOutboundHandlerAdapter() {
        val corrupted = AtomicBoolean()
        private val noiseFrames = AtomicInteger()

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (
                msg is ByteBuf &&
                !corrupted.get() &&
                msg.readableBytes() >= 32
            ) {
                if (noiseFrames.incrementAndGet() == 2) {
                    msg.setZero(msg.readerIndex(), msg.readableBytes())
                    corrupted.set(true)
                }
            }
            ctx.write(msg, promise)
        }
    }

    private companion object {
        const val CONCURRENT_DIALERS = 32
    }
}

object NoiseHandshakeStarvationHarness {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val startedAt = System.nanoTime()
        val executionRecorder = NoiseHandshakeExecutionRecorder().also { it.install() }
        val hosts = mutableListOf<Host>()
        val pingExecutor = Executors.newSingleThreadExecutor()
        try {
            val relay = newNoiseHost(listen = true).also { hosts += it }
            val monitor = newNoiseHost().also { hosts += it }
            val stormPeers = List(STORM_PEERS) { newNoiseHost().also { host -> hosts += host } }
            startNoiseHosts(hosts)

            val relayAddress = relay.listenAddresses().single()
            val pingController = Ping().dial(monitor, relay.peerId, relayAddress)
                .controller.get(10, TimeUnit.SECONDS)
            check(pingController.ping().get(2, TimeUnit.SECONDS) < 500)

            val startPinging = CompletableFuture<Unit>()
            val pingLatencies = CompletableFuture.supplyAsync(
                {
                    startPinging.join()
                    List(PING_SAMPLES) {
                        pingController.ping().get(5, TimeUnit.SECONDS)
                    }
                },
                pingExecutor
            )

            val stormConnections = stormPeers.map {
                it.network.connect(relay.peerId, relayAddress)
            }
            startPinging.complete(Unit)

            CompletableFuture.allOf(*stormConnections.toTypedArray()).get(20, TimeUnit.SECONDS)
            val sortedLatencies = pingLatencies.get(20, TimeUnit.SECONDS).sorted()
            val p95 = sortedLatencies[(sortedLatencies.size * 95 + 99) / 100 - 1]
            val execution = executionRecorder.snapshot()
            check(execution.offloadedHandshakes > 0) {
                "Expected the $STORM_PEERS-peer storm to contention-gate some Noise handshakes " +
                    "onto the crypto pool, but execution was $execution"
            }
            check(execution.offloadedCryptoTasks > 0 && execution.offloadedCryptoTasksOnEventLoop == 0) {
                "Expected offloaded Noise crypto tasks to run outside Netty event loops, but execution was $execution"
            }
            val cryptoThreads = Thread.getAllStackTraces().keys
                .filter { it.name.startsWith("noise-handshake-crypto-") }
            val expectedCryptoThreads = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)
            check(cryptoThreads.size == expectedCryptoThreads) {
                "Expected one shared $expectedCryptoThreads-thread Noise crypto pool, but found " +
                    cryptoThreads.map { it.name }.sorted()
            }
            check(cryptoThreads.all { it.isDaemon }) {
                "Noise crypto threads must all be daemon threads, but found " +
                    cryptoThreads.filter { !it.isDaemon }.map { it.name }.sorted()
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            println(
                "Noise handshake storm: peers=$STORM_PEERS pingLatenciesMs=$sortedLatencies " +
                    "p95Ms=$p95 elapsedMs=$elapsedMillis execution=$execution"
            )
            check(p95 < MAX_P95_MILLIS) {
                "Established ping p95 was ${p95}ms during $STORM_PEERS concurrent Noise handshakes; " +
                    "handshake crypto must not block the shared Netty worker"
            }
        } finally {
            pingExecutor.shutdownNow()
            stopNoiseHosts(hosts)
        }
    }
}

object NoiseHandshakePathHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.singleOrNull()) {
            "inline" -> verifySingleInlineHandshake()
            "sequential" -> verifySequentialInlineHandshakes()
            else -> error("Expected exactly one harness mode, either 'inline' or 'sequential', but received ${args.toList()}")
        }
    }

    private fun verifySingleInlineHandshake() {
        val executionRecorder = NoiseHandshakeExecutionRecorder().also { it.install() }
        val hosts = mutableListOf<Host>()
        try {
            val relay = newNoiseHost(listen = true).also { hosts += it }
            val dialer = newNoiseHost().also { hosts += it }
            startNoiseHosts(hosts)

            dialer.network.connect(relay.peerId, relay.listenAddresses().single())
                .get(5, TimeUnit.SECONDS)

            val execution = executionRecorder.snapshot()
            check(execution.inlineHandshakes == 2 && execution.offloadedHandshakes == 0) {
                "Expected both sides of one uncontended Noise handshake to use the inline path, but execution was $execution"
            }
            check(execution.inlineCryptoTasks > 0 && execution.inlineCryptoTasksOffEventLoop == 0) {
                "Expected all inline Noise crypto tasks to run on their Netty event loops, but execution was $execution"
            }
            println("Noise handshake inline path: execution=$execution")
        } finally {
            stopNoiseHosts(hosts)
        }
    }

    private fun verifySequentialInlineHandshakes() {
        val executionRecorder = NoiseHandshakeExecutionRecorder().also { it.install() }
        val hosts = mutableListOf<Host>()
        try {
            val relay = newNoiseHost(listen = true).also { hosts += it }
            val dialer = newNoiseHost().also { hosts += it }
            startNoiseHosts(hosts)
            val relayAddress = relay.listenAddresses().single()

            val startedAt = System.nanoTime()
            repeat(SEQUENTIAL_HANDSHAKES) {
                val connection = dialer.network.connect(relay.peerId, relayAddress)
                    .get(5, TimeUnit.SECONDS)
                connection.close().get(5, TimeUnit.SECONDS)
                waitForConnectionsToClose(dialer, relay)
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            val execution = executionRecorder.snapshot()
            check(execution.inlineHandshakes == SEQUENTIAL_HANDSHAKES * 2 && execution.offloadedHandshakes == 0) {
                "Expected all $SEQUENTIAL_HANDSHAKES sequential Noise handshakes on both peers to stay inline, " +
                    "but execution was $execution"
            }
            check(execution.inlineCryptoTasks > 0 && execution.inlineCryptoTasksOffEventLoop == 0) {
                "Expected all sequential inline Noise crypto tasks to run on their Netty event loops, " +
                    "but execution was $execution"
            }
            check(elapsedMillis < MAX_SEQUENTIAL_HANDSHAKE_MILLIS) {
                "$SEQUENTIAL_HANDSHAKES sequential Noise handshakes took ${elapsedMillis}ms; " +
                    "expected less than ${MAX_SEQUENTIAL_HANDSHAKE_MILLIS}ms"
            }
            println(
                "Sequential Noise handshakes: count=$SEQUENTIAL_HANDSHAKES elapsedMs=$elapsedMillis " +
                    "execution=$execution"
            )
        } finally {
            stopNoiseHosts(hosts)
        }
    }

    private fun waitForConnectionsToClose(vararg hosts: Host) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (hosts.any { it.network.connections.isNotEmpty() }) {
            check(System.nanoTime() < deadline) {
                "Closed connection remained registered after 5 seconds: " +
                    hosts.associate { it.peerId to it.network.connections.size }
            }
            Thread.yield()
        }
    }
}

private data class NoiseHandshakeExecutionSnapshot(
    val inlineHandshakes: Int,
    val offloadedHandshakes: Int,
    val inlineCryptoTasks: Int,
    val inlineCryptoTasksOffEventLoop: Int,
    val offloadedCryptoTasks: Int,
    val offloadedCryptoTasksOnEventLoop: Int
)

private class NoiseHandshakeExecutionRecorder {
    private val inlineHandshakes = AtomicInteger()
    private val offloadedHandshakes = AtomicInteger()
    private val inlineCryptoTasks = AtomicInteger()
    private val inlineCryptoTasksOffEventLoop = AtomicInteger()
    private val offloadedCryptoTasks = AtomicInteger()
    private val offloadedCryptoTasksOnEventLoop = AtomicInteger()

    fun install() {
        NoiseHandshakeExecutionTestHook.handshakeObserver = { offloaded ->
            if (offloaded) offloadedHandshakes.incrementAndGet() else inlineHandshakes.incrementAndGet()
        }
        NoiseHandshakeExecutionTestHook.cryptoTaskObserver = { offloaded, onEventLoop ->
            if (offloaded) {
                offloadedCryptoTasks.incrementAndGet()
                if (onEventLoop) offloadedCryptoTasksOnEventLoop.incrementAndGet()
            } else {
                inlineCryptoTasks.incrementAndGet()
                if (!onEventLoop) inlineCryptoTasksOffEventLoop.incrementAndGet()
            }
        }
    }

    fun snapshot() = NoiseHandshakeExecutionSnapshot(
        inlineHandshakes = inlineHandshakes.get(),
        offloadedHandshakes = offloadedHandshakes.get(),
        inlineCryptoTasks = inlineCryptoTasks.get(),
        inlineCryptoTasksOffEventLoop = inlineCryptoTasksOffEventLoop.get(),
        offloadedCryptoTasks = offloadedCryptoTasks.get(),
        offloadedCryptoTasksOnEventLoop = offloadedCryptoTasksOnEventLoop.get()
    )
}

private fun newNoiseHost(listen: Boolean = false): Host = host {
    identity {
        random(KeyType.ED25519)
    }
    if (listen) {
        network {
            listen("/ip4/127.0.0.1/tcp/0")
        }
    }
    protocols {
        +Ping()
    }
}

private fun startNoiseHosts(hosts: List<Host>) {
    CompletableFuture.allOf(*hosts.map { it.start() }.toTypedArray()).get(20, TimeUnit.SECONDS)
}

private fun stopNoiseHosts(hosts: List<Host>) {
    if (hosts.isEmpty()) return
    CompletableFuture.allOf(*hosts.asReversed().map { it.stop() }.toTypedArray())
        .get(20, TimeUnit.SECONDS)
}

private fun runNoiseHandshakeHarness(mode: String, timeoutSeconds: Long): String {
    val javaExecutable = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString()
    val process = ProcessBuilder(
        javaExecutable,
        "-cp",
        System.getProperty("java.class.path"),
        NoiseHandshakePathHarness::class.java.name,
        mode
    ).redirectErrorStream(true).start()
    val output = CompletableFuture.supplyAsync {
        process.inputStream.bufferedReader().use { it.readText() }
    }
    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
    val processOutput = output.get(5, TimeUnit.SECONDS)
    print(processOutput)

    assertThat(finished)
        .withFailMessage(
            "Noise handshake %s child JVM did not finish within %s seconds:\n%s",
            mode,
            timeoutSeconds,
            processOutput
        )
        .isTrue()
    assertThat(process.exitValue())
        .withFailMessage("Noise handshake %s child JVM failed:\n%s", mode, processOutput)
        .isZero()
    return processOutput
}

private const val STORM_PEERS = 160
private const val PING_SAMPLES = 20
private const val MAX_P95_MILLIS = 500
private const val SEQUENTIAL_HANDSHAKES = 20
private const val MAX_SEQUENTIAL_HANDSHAKE_MILLIS = 20_000
