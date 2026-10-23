package io.libp2p.transport

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Regression coverage for an issue where `PlainNettyTransport.close()` blocks for the
 * default Netty `shutdownGracefully` quiet period (2 seconds) on every call, even when
 * no traffic is in flight to drain.
 *
 * The current `PlainNettyTransport.close()` implementation chains the boss/worker
 * `shutdownGracefully()` futures via `CompletableFuture.allOf(...).thenApply { }` — the
 * caller's `close()` future does not resolve until `shutdownGracefully()` does. This is
 * functionally correct (the previous implementation called `shutdownGracefully()` and
 * dropped the returned future on the floor, so callers could not reliably wait for the
 * Netty event-loop threads to exit). But because the caller of
 * `shutdownGracefully(quietPeriod, timeout, unit)` is invoked with **no arguments**,
 * Netty's defaults of `quietPeriod = 2 s`, `timeout = 15 s`, `unit = SECONDS` apply.
 * The 2-second quiet period is the time the executor will keep the event loop alive
 * after the last submitted task finishes, in case more work is coming. For a transport
 * that is being torn down in test fixtures or in short-lived host lifecycles, no more
 * work IS coming — so the 2-second wait is pure latency.
 *
 * The cumulative effect across 20 sequential `host.start() / host.stop()` cycles in a
 * downstream test like UrlResolver's `testCloseDoesNotLeakDispatchersIoThreads` is
 * 20 × 2 s = 40 s of pure quiet-period wait, well past the test's `@Timeout(30)`.
 * Before this fix that test (and 11 of its siblings) timed out at @Timeout in
 * UrlResolver's `kotlin.build (remote)` run [`7f19e875`](https://buildtest.kotlin.build/run?id=7f19e875)
 * the moment kompile-cli's batched Coursier resolution (PR #147) flipped the assembled
 * classpath order so this fork's classes won the duplicate-FQN race against
 * `io.libp2p:jvm-libp2p:1.2.2-RELEASE` on the test classpath.
 *
 * The fix is to pass an explicit `quietPeriod = 0` to `shutdownGracefully(...)`. With
 * a zero quiet period, the event loop terminates as soon as its run-loop notices the
 * shutdown flag, and the returned future resolves in milliseconds — preserving the
 * caller-can-wait correctness contract introduced in PR #412 without paying the
 * default 2-second tax on every close.
 *
 * What this test pins:
 *   - 20 successive `TcpTransport.listen(...).close()` cycles complete in < 5 seconds
 *     wall clock total. With the unfixed code each iteration takes ≥ 2 s and the loop
 *     takes ≥ 40 s; with the fix each iteration takes ≪ 100 ms and the loop is
 *     comfortably under 5 s.
 *   - The stricter assertion on a single close (≤ 1 s) catches the same regression in
 *     isolation, so a future change that re-introduces a per-close wait of any size
 *     fails this test even if it is only 1 s instead of 2 s.
 */
@Tag("transport")
class PlainNettyTransportShutdownTimingTest {

    @Test
    fun `single close should resolve well below the default 2 second quiet period`() {
        val transport = TcpTransport(NullConnectionUpgrader())
        transport.initialize()
        // Bind to an ephemeral port so the test does not collide with sibling tests.
        transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { _ -> }, null).get(5, TimeUnit.SECONDS)

        val elapsedMs = measureTimeMillis {
            transport.close().get(10, TimeUnit.SECONDS)
        }

        // Default quietPeriod is 2 s. The fix uses quietPeriod = 0, which makes the
        // close-future resolve in tens of milliseconds even on a slow CI host. We give
        // a 1-second budget so this stays unambiguous on any reasonable hardware while
        // still failing decisively if the default 2 s quiet period is ever restored.
        assertTrue(
            elapsedMs < 1_000,
            "PlainNettyTransport.close() returned in ${elapsedMs}ms — should be well " +
                "under the default 2-second shutdownGracefully quiet period. If this " +
                "test fails after a future refactor, check whether shutdownGracefully " +
                "was called with no arguments (defaults to 2 s quiet) instead of " +
                "shutdownGracefully(0, ..., TimeUnit.SECONDS)."
        )
    }

    @Test
    fun `20 sequential listen-then-close cycles complete in under 5 seconds`() {
        // Reproduces the failure shape that downstream consumers (UrlResolver's
        // testCloseDoesNotLeakDispatchersIoThreads) hit when their @Timeout-bounded
        // tests do many short-lived host lifecycles back-to-back. With the default
        // 2 s quiet period this loop takes ~ 40 s; with the fix it takes < 5 s.
        val totalElapsedMs = measureTimeMillis {
            for (i in 1..20) {
                val t = TcpTransport(NullConnectionUpgrader())
                t.initialize()
                t.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { _ -> }, null)
                    .get(5, TimeUnit.SECONDS)
                t.close().get(10, TimeUnit.SECONDS)
            }
        }

        assertTrue(
            totalElapsedMs < 5_000,
            "20 sequential listen/close cycles took ${totalElapsedMs}ms — should be " +
                "under 5 s. Cumulative budget exhaustion of this kind is what blew " +
                "past @Timeout(30) in 12 UrlResolver stress tests in run 7f19e875 " +
                "before the fix. Per-iteration latency: ~${totalElapsedMs / 20}ms."
        )
    }
}
