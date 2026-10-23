package io.libp2p.transport

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Pins the ROOT CAUSE of the downstream "test body completes but Process timed out after 30s
 * (JVM pid gone)" CI flake: the TCP transport's Netty event-loop worker/boss threads must be
 * DAEMON threads.
 *
 * A non-daemon thread keeps the JVM alive on its own. Under heavy CI starvation an event-loop
 * worker that is slow to actually exit its run loop after `shutdownGracefully` (its shutdown task
 * can't get scheduled in time) keeps the forked test JVM alive long after the test finished — the
 * test runner then kills the process at its 30s wall-clock cap and reports a spurious failure. A
 * daemon event-loop thread cannot hold the JVM open, so the process exits promptly regardless of
 * scheduling pressure.
 *
 * This is a regression guard: the fork briefly shipped (snapshot-8) a `PlainNettyTransport` whose
 * `MultiThreadIoEventLoopGroup`s were constructed with no `ThreadFactory`, so Netty's default
 * NON-daemon `DefaultThreadFactory` was used. This test FAILS against that code (every live libp2p
 * event-loop thread is non-daemon) and passes once the transport builds its groups with a daemon
 * `DefaultThreadFactory`. It identifies the offending threads by their stack (a frame in
 * io.netty...NioEventLoop / SingleThreadEventExecutor.run) — independent of thread name — so it
 * keeps working if Netty's default naming changes.
 */
@Tag("transport")
class PlainNettyTransportDaemonThreadTest {

    private fun isNettyEventLoopThread(stack: Array<StackTraceElement>): Boolean =
        stack.any { f ->
            val c = f.className
            c.contains("io.netty.channel.nio.NioEventLoop") ||
                (c.contains("io.netty.util.concurrent.SingleThreadEventExecutor") && f.methodName == "run")
        }

    @Test
    fun `libp2p netty event-loop threads must be daemon`() {
        val transport = TcpTransport(NullConnectionUpgrader())
        transport.initialize()
        // Bind on an ephemeral port so the boss group is allocated and threads spin up.
        transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { _ -> }, null).get(5, TimeUnit.SECONDS)
        // Also dial (best effort) so the shared worker group is definitely materialised.
        try {
            transport.dial(Multiaddr("/ip4/127.0.0.1/tcp/1"), { _ -> }, null)
                .get(1, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Connection refused is expected and fine — it still spins up the worker group.
        }

        try {
            val eventLoopThreads = Thread.getAllStackTraces()
                .filter { (t, stack) -> t.isAlive && isNettyEventLoopThread(stack) }

            assertTrue(
                eventLoopThreads.isNotEmpty(),
                "Sanity: expected at least one live libp2p Netty event-loop thread after listen()/dial()."
            )

            val nonDaemon = eventLoopThreads.keys.filter { !it.isDaemon }.map { it.name }.sorted()
            assertTrue(
                nonDaemon.isEmpty(),
                "libp2p Netty event-loop threads MUST be daemon. ${nonDaemon.size} of " +
                    "${eventLoopThreads.size} live event-loop threads are NON-DAEMON: $nonDaemon. A " +
                    "non-daemon event-loop thread holds the forked JVM open when its shutdown is starved " +
                    "under CI load — the 'test body done, Process timed out 30s, JVM pid gone' flake. Build " +
                    "the transport's MultiThreadIoEventLoopGroups with a daemon DefaultThreadFactory."
            )
        } finally {
            transport.close().get(10, TimeUnit.SECONDS)
        }
    }
}
