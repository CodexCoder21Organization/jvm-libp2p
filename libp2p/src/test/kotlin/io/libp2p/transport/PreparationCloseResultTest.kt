package io.libp2p.transport

import io.libp2p.core.Host
import io.libp2p.core.Libp2pException
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Ping
import io.libp2p.transport.implementation.TransportPreparationOrdering
import io.netty.channel.DefaultEventLoop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PreparationCloseResultTest {
    @Test
    fun `close owns pending readiness start listen and connect after waiter interruption`() = closeResultProbe("results")

    @Test
    fun `blocking readiness observer does not retain the interrupted preparation waiter`() = closeResultProbe("blocking")

    @Test
    fun `closed readiness observer can reenter host stop`() = closeResultProbe("reentry")
}

private fun closeResultProbe(mode: String) {
    val output = Files.createTempFile("preparation-close-result-", ".log")
    val process = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").absolutePath,
        "-Xmx128m",
        "-Dio.netty.eventLoopThreads=1",
        "-cp",
        System.getProperty("java.class.path"),
        "io.libp2p.transport.PreparationCloseResultProbe",
        mode
    ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
    try {
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Preparation close-result child did not finish")
        val text = Files.readString(output)
        print(text)
        assertEquals(0, process.exitValue(), text)
    } finally {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        Files.deleteIfExists(output)
    }
}

object PreparationCloseResultProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.single()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val waiterReleased = AtomicBoolean()
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val callbackResult = CompletableFuture<Unit>()
        val provider = object : Provider("CloseResultSHA256", "1.0", "Owned real SHA-256 preparation gate") {
            init {
                putService(object : Service(this, "MessageDigest", "SHA-256", CloseResultDigest::class.java.name, null, null) {
                    override fun newInstance(parameter: Any?): Any {
                        if (first.compareAndSet(true, false)) {
                            entered.countDown()
                            check(release.await(30, TimeUnit.SECONDS)) { "Shared preparation was not released" }
                        }
                        return CloseResultDigest()
                    }
                })
            }
        }
        val loop = DefaultEventLoop()
        val closer = Executors.newSingleThreadExecutor()
        var pending: Host? = null
        var survivor: Host? = null
        try {
            check(Security.insertProviderAt(provider, 1) == 1)
            val subject = loop.submit(Callable { closeResultHost() }).get(10, TimeUnit.SECONDS)
            pending = subject
            check(entered.await(10, TimeUnit.SECONDS)) { "Preparation did not enter the owned provider" }
            survivor = loop.submit(Callable { closeResultHost() }).get(10, TimeUnit.SECONDS)
            val transport = subject.network.transports.single()
            val ready = transport.ready()
            val starting = subject.start()
            val listening = subject.network.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"))
            val connecting = subject.network.connect(subject.peerId, Multiaddr("/ip4/127.0.0.1/tcp/1"))
            val results = listOf(ready, starting, listening, connecting)
            check(results.none { it.isDone }) { "An operation bypassed held preparation" }
            // Force the interrupted waiter to finish before close publishes readiness.
            // This gate changes ordering only; every result comes from the public API.
            TransportPreparationOrdering.afterInterrupt = { waiter ->
                waiter.join(10_000)
                check(!waiter.isAlive) { "Closed transport retained its preparation waiter" }
                waiterReleased.set(true)
            }
            if (mode != "results") {
                ready.whenComplete { _, _ ->
                    callbackEntered.countDown()
                    try {
                        if (mode == "blocking") {
                            check(callbackRelease.await(10, TimeUnit.SECONDS)) { "Readiness callback was not released" }
                        } else {
                            subject.stop().get(10, TimeUnit.SECONDS)
                        }
                        callbackResult.complete(Unit)
                    } catch (failure: Throwable) {
                        callbackResult.completeExceptionally(failure)
                    }
                }
            }
            val stopped = closer.submit(Callable { subject.stop().get(10, TimeUnit.SECONDS) })
            if (mode != "results") {
                check(callbackEntered.await(10, TimeUnit.SECONDS)) { "Closed readiness callback did not run" }
                try {
                    check(waiterReleased.get()) { "Readiness callback ran before the interrupted waiter was released" }
                } finally {
                    callbackRelease.countDown()
                }
                callbackResult.get(10, TimeUnit.SECONDS)
            }
            stopped.get(10, TimeUnit.SECONDS)
            check(waiterReleased.get()) { "Close did not pass through the interrupted waiter gate" }
            results.forEach { result ->
                try {
                    result.get(1, TimeUnit.SECONDS)
                    error("Pending operation succeeded after transport close")
                } catch (failure: ExecutionException) {
                    var cause: Throwable = failure
                    while (cause.cause != null) cause = requireNotNull(cause.cause)
                    check(cause is Libp2pException && cause.message == "Transport is closed") {
                        "Expected Libp2pException(Transport is closed), received $cause"
                    }
                }
            }
            check(subject.listenAddresses().isEmpty() && subject.network.connections.isEmpty()) {
                "Closed host published a listener or connection"
            }
            TransportPreparationOrdering.afterInterrupt = null
            release.countDown()
            survivor.start().get(10, TimeUnit.SECONDS)
            survivor.network.transports.single().ready().get(10, TimeUnit.SECONDS)
            println("CLOSE_RESULT_MODE=$mode OBSERVERS=4 PASSED=true")
        } finally {
            callbackRelease.countDown()
            release.countDown()
            TransportPreparationOrdering.afterInterrupt = null
            try {
                pending?.stop()?.get(10, TimeUnit.SECONDS)
                survivor?.stop()?.get(10, TimeUnit.SECONDS)
            } finally {
                closer.shutdownNow()
                check(closer.awaitTermination(10, TimeUnit.SECONDS)) { "Close caller did not terminate" }
                Security.removeProvider(provider.name)
                loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
            }
        }
    }
}

private fun closeResultHost(): Host = host {
    identity { random(KeyType.ED25519) }
    protocols { +Ping() }
}

private class CloseResultDigest : MessageDigest("SHA-256") {
    private val delegate = MessageDigest.getInstance("SHA-256", "SUN")
    override fun engineGetDigestLength(): Int = delegate.digestLength
    override fun engineUpdate(input: Byte) = delegate.update(input)
    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) = delegate.update(input, offset, len)
    override fun engineDigest(): ByteArray = delegate.digest()
    override fun engineReset() = delegate.reset()
}
