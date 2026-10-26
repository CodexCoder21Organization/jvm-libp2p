package io.libp2p.reviewr85

import io.libp2p.core.*
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Question (b): NetworkImpl.connect recurses when the selected result is null. Drive it with real
 * concurrent callers at both endpoints (both endpoints retire / deny each other's surplus), with a
 * settled parent closed concurrently, and with a handler that closes fresh parents. Every connect must
 * finish and the number of transport dials per round must stay bounded.
 */
class R85RedialTerminationTest {
    private fun node(counter: (R85CountingTransport) -> Unit, listen: Boolean) = host {
        transports { add { R85CountingTransport(it).also(counter) } }
        if (listen) network { listen("/ip4/127.0.0.1/tcp/0"); listen("/ip4/127.0.0.2/tcp/0") }
        else network { listen("/ip4/127.0.0.1/tcp/0"); listen("/ip4/127.0.0.3/tcp/0") }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { add(StreamMuxerProtocol.getYamux()) }
        protocols { add(Echo()) }
    }

    private fun run(mode: String) {
        lateinit var ta: R85CountingTransport
        lateinit var tb: R85CountingTransport
        val a = node({ ta = it }, true)
        val b = node({ tb = it }, false)
        val closeFresh = AtomicBoolean(false)
        if (mode == "handler-closes") a.addConnectionHandler(ConnectionHandler { c -> if (closeFresh.get()) c.close() })
        val pool = Executors.newFixedThreadPool(10)
        var maxDials = 0
        val outcomes = mutableMapOf<String, Int>()
        try {
            a.start().get(10, SECONDS); b.start().get(10, SECONDS)
            val aAddrs = a.listenAddresses().toTypedArray(); val bAddrs = b.listenAddresses().toTypedArray()
            repeat(30) { round ->
                (a.network.connections + b.network.connections).forEach { it.close().get(10, SECONDS) }
                closeFresh.set(mode == "handler-closes")
                val before = ta.dials.get() + tb.dials.get()
                val go = CountDownLatch(1)
                val futures = (0 until 10).map { i ->
                    CompletableFuture.supplyAsync({
                        go.await()
                        if (i % 2 == 0) a.network.connect(b.peerId, *bAddrs) else b.network.connect(a.peerId, *aAddrs)
                    }, pool).thenCompose { it }
                }
                if (mode == "close-settled") {
                    CompletableFuture.runAsync({
                        go.await()
                        repeat(20) { a.network.connections.firstOrNull()?.close() }
                    }, pool)
                }
                go.countDown()
                futures.forEach { f ->
                    val r = f.handle { c, e -> if (e != null) "fail:${(e.cause ?: e)::class.simpleName}" else if (c.closeFuture().isDone) "closed" else "open" }
                        .get(15, SECONDS)
                    outcomes.merge(r, 1, Int::plus)
                }
                closeFresh.set(false)
                val dials = ta.dials.get() + tb.dials.get() - before
                if (dials > maxDials) maxDials = dials
                println("R85-B $mode round=$round dials=$dials aConns=${a.network.connections.size} bConns=${b.network.connections.size}")
            }
            println("R85-B $mode maxDials=$maxDials outcomes=$outcomes totalDials=${ta.dials.get() + tb.dials.get()}")
            // 10 callers * 2 addresses = 20 dials if nothing is shared; anything far beyond that is redial growth.
            assertThat(maxDials).isLessThanOrEqualTo(40)
        } finally {
            pool.shutdownNow()
            try { a.stop().get(10, SECONDS) } finally { b.stop().get(10, SECONDS) }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero(); assertThat(it.activeListeners).isZero()
            }
        }
    }

    @Test fun `simultaneous connects from both endpoints terminate`() = run("both")
    @Test fun `connects terminate while a settled parent is closed concurrently`() = run("close-settled")
    @Test fun `connects terminate when a handler closes every fresh parent`() = run("handler-closes")
}

internal class R85CountingTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val dials = AtomicInteger()
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> {
        dials.incrementAndGet()
        return super.dial(addr, connHandler, preHandler)
    }
}
