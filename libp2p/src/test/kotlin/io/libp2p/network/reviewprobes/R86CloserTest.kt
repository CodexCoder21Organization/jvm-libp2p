package io.libp2p.reviewr86

import io.libp2p.core.*
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.channel.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.*
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

/** R86: same stress as R85ReturnedClosedTest, plus per-physical-connection close attribution at both ends. */
class R86CloserTest {
    private val round = AtomicInteger(-1)
    private val t0 = System.nanoTime()
    private fun now() = (System.nanoTime() - t0) / 1_000_000
    // key: "<host>|<channel id>"
    private val events = ConcurrentHashMap<String, MutableList<String>>()
    private val testClosing = ConcurrentHashMap.newKeySet<Channel>()
    private fun ev(key: String, s: String) { events.computeIfAbsent(key) { java.util.Collections.synchronizedList(mutableListOf()) } += "t=${now()} r=${round.get()} $s" }
    private fun stack(skip: Int = 2) = Throwable().stackTrace.drop(skip).take(40)
        .filterNot { it.className.startsWith("io.netty.util.concurrent") && false }
        .joinToString("\n      ") { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }

    private fun instrument(name: String, conn: Connection) {
        val ch = (conn as P2PChannelOverNetty).nettyChannel
        val key = "$name|${ch.id().asShortText()}"
        ev(key, "OPEN initiator=${conn.isInitiator} local=${ch.localAddress()} remote=${ch.remoteAddress()} handlers=${ch.pipeline().names()}")
        var localClose = false
        ch.pipeline().addFirst("r86-head", object : ChannelDuplexHandler() {
            override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                localClose = true
                ev(key, "LOCAL_CLOSE byTest=${ch in testClosing} thread=${Thread.currentThread().name}\n      ${stack()}")
                ctx.close(promise)
            }
            override fun channelInactive(ctx: ChannelHandlerContext) {
                ev(key, "INACTIVE localCloseSeen=$localClose")
                ctx.fireChannelInactive()
            }
            @Deprecated("probe") override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ev(key, "HEAD_EXCEPTION $cause")
                ctx.fireExceptionCaught(cause)
            }
            override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                ev(key, "USER_EVENT $evt")
                ctx.fireUserEventTriggered(evt)
            }
        })
        ch.pipeline().addLast("r86-tail", object : ChannelInboundHandlerAdapter() {
            @Deprecated("probe") override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ev(key, "TAIL_EXCEPTION $cause\n      ${cause.stackTrace.take(15).joinToString("\n      ")}")
                ctx.fireExceptionCaught(cause)
            }
        })
        conn.closeFuture().whenComplete { _, f -> ev(key, "CLOSE_FUTURE failure=$f") }
    }

    @Test
    fun `attribute the close of connections returned by simultaneous connects`() {
        val rounds = Integer.getInteger("r86.rounds", 400)
        val callers = Integer.getInteger("r86.callers", 10)
        val a = host {
            network { listen("/ip4/127.0.0.1/tcp/0"); listen("/ip4/127.0.0.2/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }; muxers { add(StreamMuxerProtocol.getYamux()) }; protocols { add(Echo()) }
        }
        val b = host {
            network { listen("/ip4/127.0.0.1/tcp/0"); listen("/ip4/127.0.0.3/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }; muxers { add(StreamMuxerProtocol.getYamux()) }; protocols { add(Echo()) }
        }
        a.addConnectionHandler { instrument("A", it) }
        b.addConnectionHandler { instrument("B", it) }
        listOf("A" to a, "B" to b).forEach { (name, h) ->
            h.addStreamVisitor(ChannelVisitor { stream ->
                val key = "$name|${(stream.connection as P2PChannelOverNetty).nettyChannel.id().asShortText()}"
                stream.getProtocol().whenComplete { p, f ->
                    if (p == "/libp2p/connection-selection/1.0.0") ev(key, "SELECTION_STREAM initiator=${stream.isInitiator}")
                    if (f != null) ev(key, "STREAM_PROTOCOL_FAIL initiator=${stream.isInitiator} $f")
                }
            })
        }
        val pool = Executors.newFixedThreadPool(callers)
        val bad = mutableListOf<String>()
        val badChannels = LinkedHashSet<Pair<String, Channel>>()
        try {
            a.start().get(10, SECONDS); b.start().get(10, SECONDS)
            val aAddrs = a.listenAddresses().toTypedArray(); val bAddrs = b.listenAddresses().toTypedArray()
            repeat(rounds) { r ->
                round.set(r)
                (a.network.connections + b.network.connections).forEach {
                    testClosing += (it as P2PChannelOverNetty).nettyChannel
                    it.close().get(10, SECONDS)
                }
                round.set(r * 1000 + 1) // marks the connect phase of round r
                val go = CountDownLatch(1)
                val fs = (0 until callers).map { i ->
                    CompletableFuture.supplyAsync({
                        go.await()
                        val f = if (i % 2 == 0) a.network.connect(b.peerId, *bAddrs) else b.network.connect(a.peerId, *aAddrs)
                        f.thenApply { c -> Triple(i % 2 == 0, c, c.closeFuture().isDone) }
                    }, pool).thenCompose { it }
                }
                go.countDown()
                val results = fs.map { it.get(15, SECONDS) }
                round.set(r * 1000 + 2) // echo phase
                results.forEach { (fromA, c, _) ->
                    val h = if (fromA) a else b
                    val key = "${if (fromA) "A" else "B"}|${(c as P2PChannelOverNetty).nettyChannel.id().asShortText()}"
                    h.newStream<io.libp2p.tools.EchoController>(listOf("/test/echo"), c).controller
                        .thenCompose { it.echo("x") }.handle { _, f -> if (f != null) ev(key, "ECHO_FAIL $f") }.get(10, SECONDS)
                }
                round.set(r * 1000 + 3) // check phase
                results.forEach { (fromA, c, closedAtReturn) ->
                    if (c.closeFuture().isDone) {
                        val ch = (c as P2PChannelOverNetty).nettyChannel
                        bad += "round=$r fromA=$fromA closedAtReturn=$closedAtReturn conn=$c"
                        badChannels += (if (fromA) "A" else "B") to ch
                    }
                }
            }
        } finally {
            println("R86 bad=${bad.size}")
            bad.forEach { println("R86 closed $it") }
            // Join the two views of each closed physical connection by address pair.
            badChannels.forEach { (name, ch) ->
                val l = ch.localAddress().toString(); val rem = ch.remoteAddress().toString()
                println("R86 ===== physical connection $name local=$l remote=$rem")
                events.forEach { (k, list) ->
                    val open = list.firstOrNull() ?: ""
                    if ((open.contains("local=$l") && open.contains("remote=$rem")) || (open.contains("local=$rem") && open.contains("remote=$l")))
                        synchronized(list) { list.forEach { println("R86 [$k] $it") } }
                }
            }
            pool.shutdownNow()
            try { a.stop().get(10, SECONDS) } finally { b.stop().get(10, SECONDS) }
        }
        assertThat(bad).describedAs("connections returned by connect that were closed").isEmpty()
    }
}
