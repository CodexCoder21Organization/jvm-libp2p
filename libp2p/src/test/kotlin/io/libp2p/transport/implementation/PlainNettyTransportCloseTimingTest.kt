package io.libp2p.transport.implementation

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.NullConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Regression test for the `PlainNettyTransport.close()` slow-shutdown bug.
 *
 * Before the fix, `close()` called `workerGroup.shutdownGracefully()` and
 * `bossGroup.shutdownGracefully()` with no arguments. Netty's no-arg
 * `shutdownGracefully()` defaults to a 2-second `quietPeriod` and 15-second
 * timeout — the returned future cannot complete until 2 seconds have elapsed
 * with no new tasks submitted, even on an event loop that is already idle.
 * Awaiting that future from `close()` makes every transport tear-down block
 * for at least 2 seconds, which is unacceptable for callers that create and
 * close many transports per JVM (a single downstream stress suite was
 * spending 2.2s per close on a quiescent transport, hitting test
 * timeouts).
 *
 * The fix passes `quietPeriod = 0` so shutdown completes as soon as the
 * event loops drain, restoring sub-second close on a quiescent transport.
 */
@Tag("transport")
class PlainNettyTransportCloseTimingTest {

    @Test
    fun `close completes within 1 second on quiescent transport`() {
        val transport = TcpTransport(NullConnectionUpgrader())
        transport.initialize()

        // Bind a listener so the worker / boss event loops are actually started.
        // Without a bind, the lazyVar-backed groups are never instantiated and
        // close() trivially returns fast — that would not exercise the
        // shutdownGracefully path the fix targets.
        val nullHandler = ConnectionHandler { }
        transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), nullHandler)
            .get(5, TimeUnit.SECONDS)

        val start = System.nanoTime()
        transport.close().get(5, TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(
            elapsedMs < 1000,
            "PlainNettyTransport.close() took ${elapsedMs}ms on a quiescent transport. " +
                "Expected < 1000ms. Netty's default shutdownGracefully() quietPeriod is 2 " +
                "seconds; if close() awaits that future without overriding the quietPeriod, " +
                "every close() blocks for ~2s. Pass `quietPeriod = 0` to shutdownGracefully() " +
                "to fix."
        )
    }
}
