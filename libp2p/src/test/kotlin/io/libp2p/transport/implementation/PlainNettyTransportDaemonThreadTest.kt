package io.libp2p.transport.implementation

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.NullConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.SECONDS

/**
 * The TCP transport's Netty event-loop (NIO) worker threads MUST be daemon threads.
 *
 * Netty's `MultiThreadIoEventLoopGroup`, when constructed without an explicit `ThreadFactory`, uses
 * `DefaultThreadFactory` with `daemon = false`. A non-daemon thread keeps the JVM alive until it exits.
 * `close()` shuts the groups down correctly, but under heavy host load a worker can be slow to actually
 * exit its run loop after `shutdownGracefully` (its shutdown task isn't scheduled promptly) — and a single
 * surviving non-daemon event-loop thread then holds the whole JVM open. Downstream this shows up as a
 * process that finishes its work but never exits and is killed by a watchdog/timeout. Daemon event-loop
 * threads make that failure mode impossible. (The GossipRouter event thread is already daemon for the
 * same reason — see GossipRouterBuilder.)
 */
@Tag("tcp-transport")
class PlainNettyTransportDaemonThreadTest {
    @Test
    fun `TCP transport event-loop threads are daemon`() {
        val transport = TcpTransport(NullConnectionUpgrader())
        transport.initialize()
        try {
            // Binding a listener forces the worker (and boss) NioEventLoopGroups to be created + started.
            transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), ConnectionHandler { }).get(5, SECONDS)

            val eventLoopThreads = Thread.getAllStackTraces().filter { (t, stack) ->
                t.isAlive && stack.any { f ->
                    f.className.contains("io.netty.channel.nio.NioEventLoop") ||
                        (f.className.contains("io.netty.util.concurrent.SingleThreadEventExecutor") && f.methodName == "run")
                }
            }
            assertTrue(
                eventLoopThreads.isNotEmpty(),
                "Sanity: expected at least one live Netty event-loop thread after listen()."
            )
            val nonDaemon = eventLoopThreads.keys.filter { !it.isDaemon }.map { it.name }.sorted()
            assertTrue(
                nonDaemon.isEmpty(),
                "TCP transport Netty event-loop threads must be daemon so a slow/starved shutdownGracefully " +
                    "cannot hold the JVM open. ${nonDaemon.size} of ${eventLoopThreads.size} are NON-DAEMON: $nonDaemon"
            )
        } finally {
            transport.close().get(10, SECONDS)
        }
    }
}
