package io.libp2p.transport

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.implementation.PlainNettyTransport
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for the per-host Netty event-loop oversubscription fix.
 *
 * Historically every [PlainNettyTransport] (hence every libp2p Host) allocated its OWN
 * worker/boss event-loop groups. A JVM running N concurrent hosts — a test swarm, a relay
 * serving many peers, a netlab worker — then spawned N × (2×availableProcessors + 1)
 * event-loop threads that oversubscribed the CPU and starved the CPU-bound Noise handshake
 * past its deadline (surfacing downstream in UrlResolver as `still-handshaking>1s` /
 * `Timed out waiting for 1000 ms` peer-exchange failures on the constrained 4-shard build
 * droplet).
 *
 * The fix shares ONE reference-counted set of event-loop groups across all live transports.
 * This test pins the invariant directly: N concurrent transports hold exactly N references
 * to the single shared group (so no per-transport groups are allocated), and releasing them
 * drops the reference count back. A thread-count assertion is deliberately avoided here — a
 * shared group spins its worker threads up lazily to 2×cores as channels register, so on a
 * many-core host it can transiently hold MORE threads than a handful of lazily-spun
 * per-transport groups, which makes raw thread counts an unreliable discriminator.
 */
@Tag("transport")
class PlainNettyTransportSharedEventLoopTest {

    @Test
    fun `concurrent transports share one reference-counted event-loop group`() {
        val before = PlainNettyTransport.sharedGroupRefCountForTest()
        val n = 8
        val transports = (1..n).map { TcpTransport(NullConnectionUpgrader()).also { it.initialize() } }
        try {
            // Each live transport takes exactly one reference to the SHARED groups. Before the
            // fix each transport instead allocated its own group, so this shared counter would
            // never move (and would not exist). A delta of exactly n proves all n transports
            // are backed by the one shared worker/boss pair rather than n separate ones.
            assertEquals(
                before + n,
                PlainNettyTransport.sharedGroupRefCountForTest(),
                "Creating $n transports must add exactly $n references to the single shared " +
                    "event-loop group (they share one bounded group, not one group each)."
            )
        } finally {
            transports.forEach { it.close().get(5, TimeUnit.SECONDS) }
        }
        // Every close releases its reference; the shared groups are torn down when the count
        // returns to its starting value (or 0 if this test's transports were the only ones).
        assertEquals(
            before,
            PlainNettyTransport.sharedGroupRefCountForTest(),
            "Closing all $n transports must release all $n shared-group references."
        )
    }
}
