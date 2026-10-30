package io.libp2p.protocol

import io.libp2p.core.Connection
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PingConcurrentLifetimeTest {
    @Test
    fun `concurrent last closes and new registrations keep every captured scheduler usable`() {
        val protocol = PingProtocol().apply { pingTimeout = Duration.ofDays(1) }
        val client = concurrentPingHost(PingBinding(protocol))
        val server = concurrentPingHost(Ping(), listen = true)
        val workers = Executors.newFixedThreadPool(16)
        val usedSchedulers = concurrentPingSchedulerIdentitySet()
        var testFailure: Throwable? = null

        try {
            startConcurrentPingHost(server)
            startConcurrentPingHost(client)

            var activeStreams = listOf(openConcurrentPing(client, server))
            repeat(8) { round ->
                usedSchedulers.add(protocol.timeoutScheduler)
                val newlyOpened = ConcurrentLinkedQueue<ConcurrentPingStream>()
                val closeActions = activeStreams.map { opened -> { closeConcurrentPing(opened) } }
                val openActions = List(8) {
                    {
                        newlyOpened.add(openConcurrentPing(client, server))
                        Unit
                    }
                }
                awaitConcurrentPingActions(
                    runConcurrentPingActions(workers, closeActions + openActions),
                    "last-close/new-open race round $round"
                )

                activeStreams = newlyOpened.toList()
                assertThat(activeStreams).hasSize(8)
                assertConcurrentPingDistinctRoots(
                    activeStreams.map { it.connection },
                    "round $round opens each Ping stream on a distinct root"
                )
                val activeScheduler = protocol.timeoutScheduler
                usedSchedulers.add(activeScheduler)
                assertThat(activeScheduler.isShutdown)
                    .`as`("round $round streams retain a running captured scheduler")
                    .isFalse()
                val pingActions = activeStreams.map { opened ->
                    {
                        assertConcurrentPingWorks(opened.controller, "round $round stream still pings after concurrent close/open")
                        Unit
                    }
                }
                awaitConcurrentPingActions(
                    runConcurrentPingActions(workers, pingActions),
                    "post-race Ping round $round"
                )
            }

            usedSchedulers.add(protocol.timeoutScheduler)
            val finalCloses = activeStreams.map { opened -> { closeConcurrentPing(opened) } }
            awaitConcurrentPingActions(
                runConcurrentPingActions(workers, finalCloses),
                "final Ping stream closes"
            )
            usedSchedulers.forEachIndexed { index, scheduler ->
                assertConcurrentPingSchedulerTerminated(scheduler, "captured scheduler $index after the final release")
            }
        } catch (failure: Throwable) {
            testFailure = failure
        }

        usedSchedulers.add(protocol.timeoutScheduler)
        finishConcurrentPingTest(
            testFailure,
            listOf(
                { client.stop().get(10, TimeUnit.SECONDS) },
                { server.stop().get(10, TimeUnit.SECONDS) },
                { shutdownConcurrentPingSchedulers(usedSchedulers) },
                { shutdownConcurrentPingExecutor(workers) }
            )
        )
    }

    @Test
    fun `concurrent duplicate closes release only their own initiator`() {
        val protocol = PingProtocol().apply { pingTimeout = Duration.ofDays(1) }
        val client = concurrentPingHost(PingBinding(protocol))
        val server = concurrentPingHost(Ping(), listen = true)
        val workers = Executors.newFixedThreadPool(12)
        val usedSchedulers = concurrentPingSchedulerIdentitySet()
        var testFailure: Throwable? = null

        try {
            startConcurrentPingHost(server)
            startConcurrentPingHost(client)

            val first = openConcurrentPing(client, server)
            val sibling = openConcurrentPing(client, server)
            assertThat(first.connection).isNotSameAs(sibling.connection)
            val scheduler = protocol.timeoutScheduler
            usedSchedulers.add(scheduler)

            val duplicateCloses = List(12) {
                {
                    assertThatCode { first.stream.close().get(10, TimeUnit.SECONDS) }
                        .`as`("each concurrent close caller on the same real stream completes")
                        .doesNotThrowAnyException()
                    Unit
                }
            }
            awaitConcurrentPingActions(
                runConcurrentPingActions(workers, duplicateCloses),
                "concurrent duplicate close"
            )
            assertThatCode { first.stream.closeFuture().get(10, TimeUnit.SECONDS) }
                .`as`("the duplicate-close stream reaches its terminal state")
                .doesNotThrowAnyException()
            awaitConcurrentPingCloseCallbacks(first.stream)

            assertThat(scheduler.isShutdown)
                .`as`("one closed initiator does not stop the scheduler still owned by its sibling")
                .isFalse()
            assertConcurrentPingWorks(sibling.controller, "the sibling can ping while it retains the scheduler")
            assertThat(scheduler.isShutdown)
                .`as`("the scheduler remains running after the sibling ping completes")
                .isFalse()

            closeConcurrentPing(sibling)
            assertConcurrentPingSchedulerTerminated(scheduler, "the scheduler after the sibling releases it")
        } catch (failure: Throwable) {
            testFailure = failure
        }

        usedSchedulers.add(protocol.timeoutScheduler)
        finishConcurrentPingTest(
            testFailure,
            listOf(
                { client.stop().get(10, TimeUnit.SECONDS) },
                { server.stop().get(10, TimeUnit.SECONDS) },
                { shutdownConcurrentPingSchedulers(usedSchedulers) },
                { shutdownConcurrentPingExecutor(workers) }
            )
        )
    }

    @Test
    fun `concurrent failed protocol registrations release their scheduler references without affecting a sibling`() {
        val protocol = PingProtocol().apply { pingTimeout = Duration.ofDays(1) }
        val registrationFailure = IllegalStateException("test Ping handler registration failure")
        val failRegistrations = AtomicBoolean(false)
        val binding = PingRegistrationFailureBinding(protocol, registrationFailure, failRegistrations)
        val client = concurrentPingHost(binding)
        val server = concurrentPingHost(Ping(), listen = true)
        val workers = Executors.newFixedThreadPool(12)
        val usedSchedulers = concurrentPingSchedulerIdentitySet()
        var testFailure: Throwable? = null

        try {
            startConcurrentPingHost(server)
            startConcurrentPingHost(client)

            val sibling = openConcurrentPing(client, server)
            val scheduler = protocol.timeoutScheduler
            usedSchedulers.add(scheduler)
            failRegistrations.set(true)

            repeat(4) { round ->
                val failedRoots = ConcurrentLinkedQueue<Connection>()
                val failedRegistrations: List<() -> Unit> = List(8) {
                    {
                        val failedStream = openFailedConcurrentPing(
                            client,
                            server,
                            registrationFailure,
                            "round $round reports the original Ping registration failure"
                        )
                        failedRoots.add(failedStream.connection)
                        closeConcurrentPingStream(failedStream)
                        Unit
                    }
                }
                awaitConcurrentPingActions(
                    runConcurrentPingActions(workers, failedRegistrations),
                    "failed Ping registration round $round"
                )
                assertConcurrentPingDistinctRoots(
                    failedRoots,
                    "failed Ping registration round $round uses distinct roots"
                )
            }

            assertThat(scheduler.isShutdown)
                .`as`("failed registrations do not shut down the scheduler retained by the sibling")
                .isFalse()
            assertConcurrentPingWorks(sibling.controller, "the sibling remains usable after failed registrations")
            closeConcurrentPing(sibling)
            assertConcurrentPingSchedulerTerminated(scheduler, "the scheduler after the sibling's final release")
        } catch (failure: Throwable) {
            testFailure = failure
        }

        usedSchedulers.add(protocol.timeoutScheduler)
        finishConcurrentPingTest(
            testFailure,
            listOf(
                { client.stop().get(10, TimeUnit.SECONDS) },
                { server.stop().get(10, TimeUnit.SECONDS) },
                { shutdownConcurrentPingSchedulers(usedSchedulers) },
                { shutdownConcurrentPingExecutor(workers) }
            )
        )
    }

    @Test
    fun `a sole failed registration returns its captured scheduler`() {
        val failedProtocol = PingProtocol().apply { pingTimeout = Duration.ofDays(1) }
        val registrationFailure = IllegalArgumentException("single Ping handler registration failure")
        val failRegistrations = AtomicBoolean(true)
        val binding = PingRegistrationFailureBinding(failedProtocol, registrationFailure, failRegistrations)
        val client = concurrentPingHost(binding)
        val server = concurrentPingHost(Ping(), listen = true)
        val usedSchedulers = concurrentPingSchedulerIdentitySet()
        var testFailure: Throwable? = null

        try {
            startConcurrentPingHost(server)
            startConcurrentPingHost(client)

            val failedScheduler = failedProtocol.timeoutScheduler
            usedSchedulers.add(failedScheduler)
            val failedStream = openFailedConcurrentPing(
                client,
                server,
                registrationFailure,
                "the sole Ping registration reports its original pushHandler failure"
            )
            closeConcurrentPingStream(failedStream)
            assertConcurrentPingSchedulerTerminated(
                failedScheduler,
                "the scheduler captured by the only failed registration"
            )

            val replacement = failedProtocol.timeoutScheduler
            usedSchedulers.add(replacement)
            assertThat(replacement)
                .`as`("the failed protocol publishes a fresh scheduler after its only registration is released")
                .isNotSameAs(failedScheduler)
            assertThat(replacement.isShutdown).isFalse()
        } catch (failure: Throwable) {
            testFailure = failure
        }

        usedSchedulers.add(failedProtocol.timeoutScheduler)
        finishConcurrentPingTest(
            testFailure,
            listOf(
                { client.stop().get(10, TimeUnit.SECONDS) },
                { server.stop().get(10, TimeUnit.SECONDS) },
                { shutdownConcurrentPingSchedulers(usedSchedulers) }
            )
        )
    }

    @Test
    fun `a replacement scheduler serves a new root while the old executor is still terminating`() {
        val oldScheduler = ScheduledThreadPoolExecutor(1)
        val taskStarted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        val shutdownInterruptObserved = CountDownLatch(1)
        val protocol = PingProtocol().apply {
            timeoutScheduler = oldScheduler
            pingTimeout = Duration.ofDays(1)
        }
        val client = concurrentPingHost(PingBinding(protocol))
        val server = concurrentPingHost(Ping(), listen = true)
        val usedSchedulers = concurrentPingSchedulerIdentitySet().apply { add(oldScheduler) }
        var testFailure: Throwable? = null

        try {
            startConcurrentPingHost(server)
            startConcurrentPingHost(client)

            val schedulerTask = oldScheduler.submit {
                taskStarted.countDown()
                try {
                    if (!releaseTask.await(10, TimeUnit.SECONDS)) {
                        throw AssertionError("The controlled scheduler task was not released before its bound")
                    }
                } catch (_: InterruptedException) {
                    shutdownInterruptObserved.countDown()
                    // Keep termination pending until the test releases this real executor task.
                    if (!releaseTask.await(10, TimeUnit.SECONDS)) {
                        throw AssertionError("The controlled scheduler task was not released after shutdown")
                    }
                }
            }
            assertThat(taskStarted.await(10, TimeUnit.SECONDS))
                .`as`("the controlled task is running on the supplied scheduler")
                .isTrue()

            val original = openConcurrentPing(client, server)
            assertThat(protocol.timeoutScheduler).isSameAs(oldScheduler)
            closeConcurrentPing(original)

            assertThat(oldScheduler.isShutdown)
                .`as`("closing the only stream requests shutdown of its captured executor")
                .isTrue()
            assertThat(shutdownInterruptObserved.await(10, TimeUnit.SECONDS))
                .`as`("shutdownNow interrupts the controlled scheduler task")
                .isTrue()
            assertThat(oldScheduler.isTerminated)
                .`as`("the controlled task keeps executor termination pending")
                .isFalse()

            val replacement = protocol.timeoutScheduler
            usedSchedulers.add(replacement)
            assertThat(replacement)
                .`as`("the protocol publishes a replacement before the old executor finishes terminating")
                .isNotSameAs(oldScheduler)
            val reopened = openConcurrentPing(client, server)
            assertThat(reopened.connection).isNotSameAs(original.connection)
            assertThat(replacement.isShutdown).isFalse()
            assertConcurrentPingWorks(reopened.controller, "the replacement scheduler serves a fresh real root")

            releaseTask.countDown()
            assertThatCode { schedulerTask.get(10, TimeUnit.SECONDS) }
                .`as`("the controlled scheduler task completes after release")
                .doesNotThrowAnyException()
            assertConcurrentPingSchedulerTerminated(oldScheduler, "the old scheduler after its controlled task exits")
            assertConcurrentPingWorks(reopened.controller, "the replacement scheduler stays usable after old termination")
            closeConcurrentPing(reopened)
            assertConcurrentPingSchedulerTerminated(replacement, "the replacement scheduler after its last stream closes")
        } catch (failure: Throwable) {
            testFailure = failure
        }

        usedSchedulers.add(protocol.timeoutScheduler)
        finishConcurrentPingTest(
            testFailure,
            listOf(
                { releaseTask.countDown() },
                { client.stop().get(10, TimeUnit.SECONDS) },
                { server.stop().get(10, TimeUnit.SECONDS) },
                { shutdownConcurrentPingSchedulers(usedSchedulers) }
            )
        )
    }

    @Test
    fun `concurrent stream churn eventually terminates every default scheduler used`() {
        val protocol = PingProtocol().apply { pingTimeout = Duration.ofDays(1) }
        val client = concurrentPingHost(PingBinding(protocol))
        val server = concurrentPingHost(Ping(), listen = true)
        val workers = Executors.newFixedThreadPool(8)
        val usedSchedulers = concurrentPingSchedulerIdentitySet()
        var testFailure: Throwable? = null

        try {
            startConcurrentPingHost(server)
            startConcurrentPingHost(client)

            repeat(6) { round ->
                val scheduler = protocol.timeoutScheduler
                usedSchedulers.add(scheduler)
                val openedStreams = ConcurrentLinkedQueue<ConcurrentPingStream>()
                val opens = List(8) {
                    {
                        openedStreams.add(openConcurrentPing(client, server))
                        Unit
                    }
                }
                awaitConcurrentPingActions(runConcurrentPingActions(workers, opens), "stream-open round $round")

                assertThat(protocol.timeoutScheduler)
                    .`as`("opening concurrent streams in round $round keeps their captured scheduler current")
                    .isSameAs(scheduler)
                assertThat(scheduler.isShutdown)
                    .`as`("the scheduler remains running while round $round streams are open")
                    .isFalse()
                assertThat(openedStreams).hasSize(8)
                assertConcurrentPingDistinctRoots(
                    openedStreams.map { it.connection },
                    "stream-open round $round uses distinct roots"
                )

                val closes = openedStreams.map { opened -> { closeConcurrentPing(opened) } }
                awaitConcurrentPingActions(runConcurrentPingActions(workers, closes), "stream-close round $round")
                assertConcurrentPingSchedulerTerminated(scheduler, "the round $round scheduler after its final stream closes")

                assertThat(protocol.timeoutScheduler)
                    .`as`("round $round publishes a new default scheduler for later streams")
                    .isNotSameAs(scheduler)
                assertThat(protocol.timeoutScheduler.isShutdown).isFalse()
            }

            assertThat(usedSchedulers).hasSize(6)
            usedSchedulers.forEachIndexed { index, scheduler ->
                assertConcurrentPingSchedulerTerminated(scheduler, "captured default scheduler $index after stream churn")
            }
        } catch (failure: Throwable) {
            testFailure = failure
        }

        usedSchedulers.add(protocol.timeoutScheduler)
        finishConcurrentPingTest(
            testFailure,
            listOf(
                { client.stop().get(10, TimeUnit.SECONDS) },
                { server.stop().get(10, TimeUnit.SECONDS) },
                { shutdownConcurrentPingSchedulers(usedSchedulers) },
                { shutdownConcurrentPingExecutor(workers) }
            )
        )
    }
}

private data class ConcurrentPingStream(
    val connection: Connection,
    val stream: Stream,
    val controller: PingController
)

private data class FailedConcurrentPingStream(
    val connection: Connection,
    val stream: Stream
)

private class PingPushHandlerFailureStream(
    private val delegate: Stream,
    private val failure: Throwable
) : Stream by delegate {
    override fun <TMessage> pushHandler(protocolHandler: ProtocolMessageHandler<TMessage>) {
        throw failure
    }

    override fun pushHandler(handler: ChannelHandler) {
        throw failure
    }
}

private class PingRegistrationFailureBinding(
    protocol: PingProtocol,
    private val failure: Throwable,
    private val failRegistrations: AtomicBoolean
) : PingBinding(protocol) {
    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out PingController> =
        if (failRegistrations.get()) {
            super.initChannel(PingPushHandlerFailureStream(ch as Stream, failure), selectedProtocol)
        } else {
            super.initChannel(ch, selectedProtocol)
        }
}

private fun concurrentPingHost(binding: PingBinding, listen: Boolean = false): Host = host {
    if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
    transports { add(::TcpTransport) }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    protocols { add(binding) }
}

private fun startConcurrentPingHost(host: Host) {
    assertThatCode { host.start().get(10, TimeUnit.SECONDS) }
        .`as`("the real TCP/Noise/Yamux host starts")
        .doesNotThrowAnyException()
}

private fun openConcurrentPing(client: Host, server: Host): ConcurrentPingStream {
    val connection = dialConcurrentPingRoot(client, server)
    val opening = client.newStream<PingController>(listOf(CONCURRENT_PING_PROTOCOL), connection)
    var stream: Stream? = null
    assertThatCode { stream = opening.stream.get(10, TimeUnit.SECONDS) }
        .`as`("the real TCP/Noise/Yamux Ping stream opens")
        .doesNotThrowAnyException()
    var controller: PingController? = null
    assertThatCode { controller = opening.controller.get(10, TimeUnit.SECONDS) }
        .`as`("the real Ping initiator becomes available")
        .doesNotThrowAnyException()
    val opened = ConcurrentPingStream(connection, requireNotNull(stream), requireNotNull(controller))
    assertConcurrentPingWorks(opened.controller, "a newly opened Ping stream completes its request")
    return opened
}

private fun openFailedConcurrentPing(
    client: Host,
    server: Host,
    expectedFailure: Throwable,
    description: String
): FailedConcurrentPingStream {
    val connection = dialConcurrentPingRoot(client, server)
    val opening = client.newStream<PingController>(listOf(CONCURRENT_PING_PROTOCOL), connection)
    var stream: Stream? = null
    assertThatCode { stream = opening.stream.get(10, TimeUnit.SECONDS) }
        .`as`("$description publishes the created real stream")
        .doesNotThrowAnyException()
    val controllerFailure = assertThrows(
        ExecutionException::class.java,
        { opening.controller.get(10, TimeUnit.SECONDS) },
        "$description completes exceptionally"
    )
    assertThat(controllerFailure.cause)
        .`as`("$description preserves the original registration failure")
        .isSameAs(expectedFailure)
    return FailedConcurrentPingStream(connection, requireNotNull(stream))
}

private fun dialConcurrentPingRoot(client: Host, server: Host): Connection {
    var connection: Connection? = null
    assertThatCode {
        connection = client.network.transports.single()
            .dial(server.listenAddresses().first(), client.network.connectionHandler, null)
            .get(10, TimeUnit.SECONDS)
    }.`as`("the client opens a new real TCP/Noise/Yamux root")
        .doesNotThrowAnyException()
    return requireNotNull(connection)
}

private fun assertConcurrentPingWorks(controller: PingController, description: String) {
    var elapsed: Long? = null
    assertThatCode { elapsed = controller.ping().get(10, TimeUnit.SECONDS) }
        .`as`(description)
        .doesNotThrowAnyException()
    assertThat(requireNotNull(elapsed)).isGreaterThanOrEqualTo(0)
}

private fun closeConcurrentPing(opened: ConcurrentPingStream) {
    closeConcurrentPingStream(opened.connection, opened.stream)
}

private fun closeConcurrentPingStream(opened: FailedConcurrentPingStream) {
    closeConcurrentPingStream(opened.connection, opened.stream)
}

private fun closeConcurrentPingStream(connection: Connection, stream: Stream) {
    assertThatCode {
        stream.close().get(10, TimeUnit.SECONDS)
        stream.closeFuture().get(10, TimeUnit.SECONDS)
        awaitConcurrentPingCloseCallbacks(stream)
        connection.close().get(10, TimeUnit.SECONDS)
        connection.closeFuture().get(10, TimeUnit.SECONDS)
    }.`as`("the real Ping stream closes and runs its close callbacks")
        .doesNotThrowAnyException()
}

private fun awaitConcurrentPingCloseCallbacks(stream: Stream) {
    val callbacksDrained = CompletableFuture<Unit>()
    (stream as P2PChannelOverNetty).nettyChannel.eventLoop().execute { callbacksDrained.complete(Unit) }
    assertThatCode { callbacksDrained.get(10, TimeUnit.SECONDS) }
        .`as`("the stream event loop drains its Ping close callbacks")
        .doesNotThrowAnyException()
}

private fun runConcurrentPingActions(
    workers: ExecutorService,
    actions: List<() -> Unit>
): List<CompletableFuture<Void>> {
    val ready = CountDownLatch(actions.size)
    val start = CountDownLatch(1)
    val futures = actions.map { action ->
        CompletableFuture.runAsync(
            Runnable {
                ready.countDown()
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw AssertionError("Concurrent Ping action did not receive its start signal")
                }
                action()
            },
            workers
        )
    }
    val allReady = ready.await(10, TimeUnit.SECONDS)
    start.countDown()
    assertThat(allReady).`as`("all concurrent Ping callers reach the shared start barrier").isTrue()
    return futures
}

private fun awaitConcurrentPingActions(futures: List<CompletableFuture<Void>>, description: String) {
    futures.forEachIndexed { index, future ->
        assertThatCode { future.get(30, TimeUnit.SECONDS) }
            .`as`("$description caller $index completes")
            .doesNotThrowAnyException()
    }
}

private fun concurrentPingSchedulerIdentitySet(): MutableSet<ScheduledExecutorService> =
    Collections.newSetFromMap(IdentityHashMap<ScheduledExecutorService, Boolean>())

private fun assertConcurrentPingDistinctRoots(connections: Collection<Connection>, description: String) {
    val roots = Collections.newSetFromMap(IdentityHashMap<Connection, Boolean>())
    roots.addAll(connections)
    assertThat(roots).`as`(description).hasSize(connections.size)
}

private fun assertConcurrentPingSchedulerTerminated(scheduler: ScheduledExecutorService, description: String) {
    assertThat(scheduler.isShutdown).`as`("$description is shut down").isTrue()
    var terminated = false
    assertThatCode { terminated = scheduler.awaitTermination(10, TimeUnit.SECONDS) }
        .`as`("$description terminates")
        .doesNotThrowAnyException()
    assertThat(terminated).`as`("$description finishes termination").isTrue()
}

private fun shutdownConcurrentPingSchedulers(schedulers: Set<ScheduledExecutorService>) {
    schedulers.forEachIndexed { index, scheduler ->
        scheduler.shutdownNow()
        if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
            throw AssertionError("Captured Ping scheduler $index did not terminate during test cleanup")
        }
    }
}

private fun shutdownConcurrentPingExecutor(executor: ExecutorService) {
    executor.shutdownNow()
    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        throw AssertionError("Concurrent Ping test workers did not terminate during cleanup")
    }
}

private fun finishConcurrentPingTest(testFailure: Throwable?, cleanupActions: List<() -> Unit>) {
    val cleanupFailures = cleanupActions.mapNotNull { action ->
        try {
            action()
            null
        } catch (failure: Throwable) {
            failure
        }
    }
    val failure = testFailure
    if (failure != null) {
        cleanupFailures.forEach { cleanupFailure ->
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
    if (cleanupFailures.isNotEmpty()) {
        val firstFailure = cleanupFailures.first()
        cleanupFailures.drop(1).forEach(firstFailure::addSuppressed)
        throw firstFailure
    }
}

private const val CONCURRENT_PING_PROTOCOL = "/ipfs/ping/1.0.0"
