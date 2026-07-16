package io.libp2p.security.noise

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Protocol
import io.libp2p.protocol.Ping
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.util.ReferenceCountUtil
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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
    fun `remote read timeout fails saturated handshakes while responder crypto is queued`() {
        val processOutput = runNoiseHandshakeHarness(
            mode = "saturated",
            timeoutSeconds = 30
        )

        assertThat(processOutput).contains("Saturated Noise handshakes:")
    }

    @Test
    fun `stress saturated Noise handshakes do not self-timeout during local work`() {
        // Unfixed develop c05e211b failed 6/10 runs with Noise ReadTimeoutException failures
        // under this same 120-dialer, 32-spinner, two-CPU, 256 MiB Serial-GC workload.
        val processOutput = runNoiseHandshakeHarness(
            mode = "cpu-saturated",
            timeoutSeconds = 30,
            jvmArgs = listOf(
                "-Xms256m",
                "-Xmx256m",
                "-XX:+UseSerialGC",
                "-XX:ActiveProcessorCount=2",
                "-Dio.netty.eventLoopThreads=2"
            )
        )

        assertThat(processOutput).contains("CPU-saturated Noise handshakes:")
    }

    @Test
    fun `responder times out silent initiator after Noise selection`() {
        val relay = newNoiseHost(listen = true)
        try {
            startNoiseHosts(listOf(relay))
            val port = requireNotNull(
                relay.listenAddresses().single().getFirstComponent(Protocol.TCP)?.stringValue
            ) {
                "Noise test relay did not expose a TCP listen address: ${relay.listenAddresses()}"
            }.toInt()

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = TimeUnit.SECONDS.toMillis(HandshakeTimeoutSec + 3L).toInt()
                writeMultistreamMessage(socket.getOutputStream(), "/multistream/1.0.0")
                writeMultistreamMessage(socket.getOutputStream(), NoiseXXSecureChannel.announce)
                socket.getOutputStream().flush()

                assertThat(readMultistreamMessage(socket.getInputStream())).isEqualTo("/multistream/1.0.0")
                assertThat(readMultistreamMessage(socket.getInputStream())).isEqualTo(NoiseXXSecureChannel.announce)

                val startedAt = System.nanoTime()
                assertThat(socket.getInputStream().read()).isEqualTo(-1)
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                println("Silent Noise initiator timed out after ${elapsedMillis}ms")
                assertThat(elapsedMillis).isBetween(
                    TimeUnit.SECONDS.toMillis(HandshakeTimeoutSec - 1L),
                    TimeUnit.SECONDS.toMillis(HandshakeTimeoutSec + 2L)
                )
            }
        } finally {
            stopNoiseHosts(listOf(relay))
        }
    }

    @Test
    fun `initiator times out silent responder while waiting for message 2`() {
        val hosts = mutableListOf<Host>()
        val relayChannel = CompletableFuture<P2PChannel>()
        val silenceResponder = DropOutboundNoiseFromFrame(firstNoiseFrameToDrop = 1)
        try {
            val relay = host {
                identity {
                    random(KeyType.ED25519)
                }
                network {
                    listen("/ip4/127.0.0.1/tcp/0")
                }
                protocols {
                    +Ping()
                }
                debug {
                    beforeSecureHandler.addHandler {
                        relayChannel.complete(it)
                        it.pushHandler(silenceResponder)
                    }
                }
            }.also { hosts += it }
            val dialer = newNoiseHost().also { hosts += it }
            startNoiseHosts(hosts)

            val connection = dialer.network.connect(relay.peerId, relay.listenAddresses().single())
            val phaseStartedAt = silenceResponder.firstDroppedAt.get(5, TimeUnit.SECONDS)

            val failure = catchThrowable {
                connection.get(HandshakeTimeoutSec + 3L, TimeUnit.SECONDS)
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartedAt)
            println("Silent Noise responder message 2 timed out after ${elapsedMillis}ms")

            assertThat(failure).isInstanceOf(ExecutionException::class.java)
            assertThat(failure).hasRootCauseInstanceOf(ReadTimeoutException::class.java)
            assertThat(elapsedMillis).isBetween(
                TimeUnit.SECONDS.toMillis(HandshakeTimeoutSec - 1L),
                TimeUnit.SECONDS.toMillis(HandshakeTimeoutSec + 2L)
            )
            relayChannel.get(5, TimeUnit.SECONDS).closeFuture().get(5, TimeUnit.SECONDS)
        } finally {
            stopNoiseHosts(hosts)
        }
    }

    @Test
    fun `responder times out silent initiator while waiting for message 3`() {
        val hosts = mutableListOf<Host>()
        val dialerChannel = CompletableFuture<P2PChannel>()
        val silenceInitiator = DropOutboundNoiseFromFrame(firstNoiseFrameToDrop = 2)
        try {
            val relay = newNoiseHost(listen = true).also { hosts += it }
            val dialer = host {
                identity {
                    random(KeyType.ED25519)
                }
                protocols {
                    +Ping()
                }
                debug {
                    beforeSecureHandler.addHandler {
                        dialerChannel.complete(it)
                        it.pushHandler(silenceInitiator)
                    }
                }
            }.also { hosts += it }
            startNoiseHosts(hosts)

            val connection = dialer.network.connect(relay.peerId, relay.listenAddresses().single())
            val phaseStartedAt = silenceInitiator.firstDroppedAt.get(5, TimeUnit.SECONDS)

            val failure = catchThrowable {
                connection.get(HandshakeTimeoutSec + 3L, TimeUnit.SECONDS)
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartedAt)
            println("Silent Noise initiator message 3 timed out after ${elapsedMillis}ms")

            assertThat(failure).isInstanceOf(ExecutionException::class.java)
            assertThat(failure).hasRootCauseInstanceOf(ConnectionClosedException::class.java)
            assertThat(elapsedMillis).isBetween(
                TimeUnit.SECONDS.toMillis(HandshakeTimeoutSec - 1L),
                TimeUnit.SECONDS.toMillis(HandshakeTimeoutSec + 2L)
            )
            dialerChannel.get(5, TimeUnit.SECONDS).closeFuture().get(5, TimeUnit.SECONDS)
        } finally {
            stopNoiseHosts(hosts)
        }
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

    private class DropOutboundNoiseFromFrame(
        private val firstNoiseFrameToDrop: Int
    ) : ChannelOutboundHandlerAdapter() {
        val firstDroppedAt = CompletableFuture<Long>()
        private val noiseFrames = AtomicInteger()
        private val dropping = AtomicBoolean()

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val shouldStartDropping =
                msg is ByteBuf &&
                    msg.readableBytes() >= 32 &&
                    noiseFrames.incrementAndGet() == firstNoiseFrameToDrop
            if (dropping.get() || shouldStartDropping) {
                dropping.set(true)
                firstDroppedAt.complete(System.nanoTime())
                ReferenceCountUtil.release(msg)
                promise.setSuccess()
                return
            }
            ctx.write(msg, promise)
        }
    }

    private companion object {
        const val CONCURRENT_DIALERS = 32
    }
}

private fun writeMultistreamMessage(output: OutputStream, message: String) {
    val bytes = "$message\n".toByteArray(Charsets.UTF_8)
    require(bytes.size < 128) { "Test multistream message is too long for its one-byte varint: $message" }
    output.write(bytes.size)
    output.write(bytes)
}

private fun readMultistreamMessage(input: InputStream): String {
    val length = input.read()
    check(length in 1 until 128) { "Expected a one-byte multistream frame length, but received $length" }
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < bytes.size) {
        val read = input.read(bytes, offset, bytes.size - offset)
        check(read >= 0) { "Socket closed after $offset of ${bytes.size} multistream frame bytes" }
        offset += read
    }
    return bytes.toString(Charsets.UTF_8).removeSuffix("\n")
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
            "saturated" -> verifySaturatedRemoteWaitsTimeOut()
            "cpu-saturated" -> verifyCpuSaturatedHandshakesDoNotSelfTimeout()
            else -> error(
                "Expected exactly one harness mode: 'inline', 'sequential', 'saturated', or 'cpu-saturated'; " +
                    "received ${args.toList()}"
            )
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

    private fun verifySaturatedRemoteWaitsTimeOut() {
        val executionRecorder = NoiseHandshakeExecutionRecorder().also { it.install() }
        val releaseSaturatedWorkers = CountDownLatch(1)
        val saturatedWorkersStarted = CountDownLatch(SATURATION_EXECUTOR_THREADS)
        val saturationThreadNumber = AtomicInteger()
        val saturatedExecutor = ThreadPoolExecutor(
            SATURATION_EXECUTOR_THREADS,
            SATURATION_EXECUTOR_THREADS,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(SATURATION_QUEUE_CAPACITY),
            { runnable ->
                Thread(
                    runnable,
                    "noise-saturation-worker-${saturationThreadNumber.incrementAndGet()}"
                ).apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy()
        )
        repeat(SATURATION_EXECUTOR_THREADS) {
            saturatedExecutor.execute {
                saturatedWorkersStarted.countDown()
                releaseSaturatedWorkers.await()
            }
        }
        val hosts = mutableListOf<Host>()
        try {
            check(saturatedWorkersStarted.await(5, TimeUnit.SECONDS)) {
                "The hermetic Noise saturation executor did not occupy all $SATURATION_EXECUTOR_THREADS workers"
            }
            NoiseHandshakeExecutionTestHook.cryptoExecutorSelector = { role, receivedFrames ->
                if (role == Role.RESP && receivedFrames == 1) saturatedExecutor else null
            }

            val relay = newNoiseHost(listen = true).also { hosts += it }
            val dialers = List(SATURATED_DIALERS) { newNoiseHost().also { host -> hosts += host } }
            startNoiseHosts(hosts)
            val relayAddress = relay.listenAddresses().single()

            val connections = dialers.map { it.network.connect(relay.peerId, relayAddress) }
            val queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (saturatedExecutor.queue.isEmpty()) {
                check(System.nanoTime() < queueDeadline) {
                    "No responder message-1 crypto task reached the hermetic saturation executor within 5 seconds"
                }
                Thread.yield()
            }
            Thread.sleep(TimeUnit.SECONDS.toMillis(HandshakeTimeoutSec + 1L))

            val connectionStates = connections.groupingBy { connection ->
                when {
                    !connection.isDone -> "pending"
                    connection.isCompletedExceptionally -> "failed"
                    else -> "succeeded"
                }
            }.eachCount()
            check(connectionStates.getOrDefault("pending", 0) == 0) {
                "Expected every initiator still waiting for responder message 2 to fail while responder crypto " +
                    "stayed saturated past the remote read timeout, but states were $connectionStates"
            }

            val failures = connections.mapIndexedNotNull { index, connection ->
                connection.handle { _, failure -> failure }.getNow(null)?.let { index to it }
            }
            check(failures.isNotEmpty()) {
                "Expected queued responder crypto to leave initiators waiting long enough to exercise the remote " +
                    "read timeout, but all $SATURATED_DIALERS dials succeeded"
            }
            val queuedResponderTasks = saturatedExecutor.queue.size
            check(failures.size == queuedResponderTasks) {
                "Expected every one of the $queuedResponderTasks queued responder message-1 crypto tasks to leave " +
                    "exactly one initiator waiting until timeout, but ${failures.size} dials failed"
            }
            val readTimeoutFailures = executionRecorder.failuresOfType(ReadTimeoutException::class.java)
            check(readTimeoutFailures.size == failures.size) {
                "Expected exactly one observable read-timeout failure for each of ${failures.size} initiators " +
                    "that failed while waiting for responder message 2, but observed ${readTimeoutFailures.size}; " +
                    "dial failures=" +
                    failures.joinToString { (index, failure) -> "dialer-$index=${formatCauseChain(failure)}" } +
                    "; Noise endpoint failures=${executionRecorder.failureCauseChains()}"
            }
            println(
                "Saturated Noise handshakes: succeeded=${SATURATED_DIALERS - failures.size} timedOut=${failures.size} " +
                    "readTimeoutFailures=${readTimeoutFailures.size} executorThreads=$SATURATION_EXECUTOR_THREADS " +
                    "queuedResponderTasks=$queuedResponderTasks"
            )
        } finally {
            NoiseHandshakeExecutionTestHook.cryptoExecutorSelector = null
            releaseSaturatedWorkers.countDown()
            saturatedExecutor.shutdown()
            if (!saturatedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                saturatedExecutor.shutdownNow()
                saturatedExecutor.awaitTermination(5, TimeUnit.SECONDS)
            }
            stopNoiseHosts(hosts)
        }
    }

    private fun verifyCpuSaturatedHandshakesDoNotSelfTimeout() {
        val stopSpinners = AtomicBoolean()
        val spinners = List(SATURATION_SPINNERS) { index ->
            thread(name = "noise-cpu-spinner-$index", isDaemon = true, start = false) {
                var value = index.toDouble()
                while (!stopSpinners.get()) {
                    value += Math.sin(value)
                    if (value.isNaN()) value = index.toDouble()
                }
            }.apply { priority = Thread.MAX_PRIORITY }
        }
        val hosts = mutableListOf<Host>()
        try {
            val relay = newNoiseHost(listen = true).also { hosts += it }
            val dialers = List(SATURATED_DIALERS) { newNoiseHost().also { host -> hosts += host } }
            startNoiseHosts(hosts)
            val relayAddress = relay.listenAddresses().single()

            spinners.forEach { it.start() }
            val connections = dialers.map { it.network.connect(relay.peerId, relayAddress) }
            CompletableFuture.allOf(*connections.toTypedArray())
                .handle { _, _ -> Unit }
                .get(20, TimeUnit.SECONDS)

            val failures = connections.mapIndexedNotNull { index, connection ->
                connection.handle { _, failure -> failure }.getNow(null)?.let { index to it }
            }
            check(failures.isEmpty()) {
                "Local Noise crypto queueing caused ${failures.size}/$SATURATED_DIALERS real dials to fail " +
                    "under CPU saturation even though both peers remained healthy: " +
                    failures.joinToString { (index, failure) -> "dialer-$index=${formatCauseChain(failure)}" }
            }
            println("CPU-saturated Noise handshakes: connected=${connections.size} failures=0")
        } finally {
            stopSpinners.set(true)
            spinners.forEach { it.join(2_000) }
            stopNoiseHosts(hosts)
        }
    }

    private fun formatCauseChain(failure: Throwable): String =
        generateSequence(failure) { current -> current.cause?.takeUnless { it === current } }
            .take(8)
            .joinToString(" <- ") { cause ->
                cause.message?.let { "${cause.javaClass.name}: $it" } ?: cause.javaClass.name
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
    private val failures = ConcurrentLinkedQueue<Throwable>()

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
        NoiseHandshakeExecutionTestHook.failureObserver = { failures += it }
    }

    fun snapshot() = NoiseHandshakeExecutionSnapshot(
        inlineHandshakes = inlineHandshakes.get(),
        offloadedHandshakes = offloadedHandshakes.get(),
        inlineCryptoTasks = inlineCryptoTasks.get(),
        inlineCryptoTasksOffEventLoop = inlineCryptoTasksOffEventLoop.get(),
        offloadedCryptoTasks = offloadedCryptoTasks.get(),
        offloadedCryptoTasksOnEventLoop = offloadedCryptoTasksOnEventLoop.get()
    )

    fun failureCauseChains(): List<String> = failures.map { failure ->
        generateSequence(failure) { current -> current.cause?.takeUnless { it === current } }
            .take(8)
            .joinToString(" <- ") { cause ->
                cause.message?.let { "${cause.javaClass.name}: $it" } ?: cause.javaClass.name
            }
    }

    fun failuresOfType(type: Class<out Throwable>): List<Throwable> = failures.filter { failure ->
        generateSequence(failure) { current -> current.cause?.takeUnless { it === current } }
            .any(type::isInstance)
    }
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

private fun runNoiseHandshakeHarness(
    mode: String,
    timeoutSeconds: Long,
    jvmArgs: List<String> = emptyList()
): String {
    val javaExecutable = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString()
    val commandPrefix = if (mode == "cpu-saturated") twoCpuAffinityPrefix() else emptyList()
    val command = commandPrefix + listOf(javaExecutable) + jvmArgs + listOf(
        "-cp",
        System.getProperty("java.class.path"),
        NoiseHandshakePathHarness::class.java.name,
        mode
    )
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
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

private fun twoCpuAffinityPrefix(): List<String> {
    if (System.getProperty("os.name") != "Linux") return emptyList()
    val allowedList = java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/self/status"))
        .first { it.startsWith("Cpus_allowed_list:") }
        .substringAfter(':')
        .trim()
    val allowedCpus = allowedList.split(',').flatMap { segment ->
        val bounds = segment.split('-').map(String::toInt)
        if (bounds.size == 1) bounds else (bounds[0]..bounds[1]).toList()
    }
    check(allowedCpus.size >= 2) {
        "CPU-saturated Noise test needs two CPUs, but the process is allowed only $allowedList"
    }
    return listOf("taskset", "--cpu-list", allowedCpus.take(2).joinToString(","))
}

private const val STORM_PEERS = 160
private const val PING_SAMPLES = 20
private const val MAX_P95_MILLIS = 500
private const val SEQUENTIAL_HANDSHAKES = 20
private const val MAX_SEQUENTIAL_HANDSHAKE_MILLIS = 20_000
private const val SATURATED_DIALERS = 120
private const val SATURATION_EXECUTOR_THREADS = 2
private const val SATURATION_QUEUE_CAPACITY = 256
private const val SATURATION_SPINNERS = 32
