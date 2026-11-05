package io.libp2p.transport

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Races listen and close through the real transport API. The transport owns its
 * OS-assigned listener from the first bind; no free-port probe releases that ownership.
 * A retained listener remains visible through listenAddresses after close. When bind
 * completed before close, also check its observed endpoint with one after-close bind.
 */
@Tag("transport")
class PlainNettyTransportConcurrentListenCloseTest {
    @Test
    fun `concurrent listen and close on the same port must not leak the listener channel`() {
        val iterations = 200
        var firstFailure = -1
        var firstFailureMessage = ""
        var failureCount = 0

        for (i in 1..iterations) {
            val transport = TcpTransport(NullConnectionUpgrader())
            transport.initialize()
            val addresses = Collections.synchronizedSet(mutableSetOf<Multiaddr>())
            val listenFuture = transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { _ -> }, null)
                .whenComplete { _, failure ->
                    if (failure == null) addresses.addAll(transport.listenAddresses())
                }
            val closeFuture = transport.close()
            try {
                listenFuture.get(10, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.ExecutionException) {
                // Closing before bind completes may legitimately fail the listen operation.
            }
            closeFuture.get(10, TimeUnit.SECONDS)
            val failures = listenerReleaseFailures(transport, addresses.toList())
            if (failures.isNotEmpty()) {
                failureCount++
                if (firstFailure < 0) {
                    firstFailure = i
                    firstFailureMessage = "iter=$i: ${failures.joinToString()}"
                }
            }
        }
        assertEquals(
            0,
            failureCount,
            "Listener release failed after $failureCount/$iterations listen-close races. First failure: $firstFailureMessage"
        )
    }

    @Test
    fun `parallel listen and close from separate threads must not leak the listener channel`() {
        val iterations = 200
        val failureCount = AtomicInteger(0)
        var firstFailureIter = -1
        var firstFailureMessage = ""

        for (i in 1..iterations) {
            val transport = TcpTransport(NullConnectionUpgrader())
            transport.initialize()
            val addresses = Collections.synchronizedSet(mutableSetOf<Multiaddr>())
            val barrier = CountDownLatch(1)
            val closeFailure = AtomicReference<Throwable>()
            val listenFailure = AtomicReference<Throwable>()
            val listenThread = thread(name = "listen-$i", isDaemon = true) {
                barrier.await()
                try {
                    transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { _ -> }, null)
                        .thenRun { addresses.addAll(transport.listenAddresses()) }
                        .get(5, TimeUnit.SECONDS)
                } catch (_: io.libp2p.core.Libp2pException) {
                    // close() may win before listen() begins.
                } catch (_: java.util.concurrent.ExecutionException) {
                    // close() may win while the asynchronous bind is pending.
                } catch (failure: Throwable) {
                    listenFailure.set(failure)
                }
            }
            val closeThread = thread(name = "close-$i", isDaemon = true) {
                barrier.await()
                try {
                    transport.close().get(10, TimeUnit.SECONDS)
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                }
            }
            barrier.countDown()
            listenThread.join(15_000)
            closeThread.join(15_000)
            assertTrue(!listenThread.isAlive && !closeThread.isAlive, "Listen and close threads must finish at iteration $i")
            listenFailure.get()?.let { throw AssertionError("Transport listen failed at iteration $i", it) }
            closeFailure.get()?.let { throw AssertionError("Transport close failed at iteration $i", it) }
            val failures = listenerReleaseFailures(transport, addresses.toList())
            if (failures.isNotEmpty()) {
                if (failureCount.incrementAndGet() == 1) {
                    firstFailureIter = i
                    firstFailureMessage = "iter=$i: ${failures.joinToString()}"
                }
            }
        }
        assertTrue(
            failureCount.get() == 0,
            "Listener release failed after ${failureCount.get()}/$iterations parallel listen-close races " +
                "(first at iter $firstFailureIter). $firstFailureMessage"
        )
    }

    @Test
    fun `completed listener close releases its actual endpoint`() {
        repeat(200) {
            val transport = TcpTransport(NullConnectionUpgrader())
            try {
                transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { }).get(5, TimeUnit.SECONDS)
                val address = transport.listenAddresses().single()
                transport.close().get(10, TimeUnit.SECONDS)
                assertEquals(emptyList<String>(), listenerReleaseFailures(transport, listOf(address)))
            } finally {
                transport.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}

private fun listenerReleaseFailures(transport: TcpTransport, observed: List<Multiaddr>): List<String> {
    val retained = transport.listenAddresses()
    val failures = mutableListOf<String>()
    if (transport.activeListeners != 0) failures += "Transport still reports ${transport.activeListeners} listeners after close"
    for (address in (observed + retained).distinct()) {
        val port = address.getFirstComponent(Protocol.TCP)!!.stringValue!!.toInt()
        try {
            ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port)) }
        } catch (failure: BindException) {
            val owner = if (address in retained) "this transport (retained listener)" else "an unknown owner"
            failures += "Endpoint $address remains held by $owner after close: ${failure.message}"
        }
    }
    return failures
}
