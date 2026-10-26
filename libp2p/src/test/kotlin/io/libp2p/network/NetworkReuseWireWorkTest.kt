package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.StreamPromise
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerDebug
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkReuseWireWorkTest {
    @ParameterizedTest
    @ValueSource(strings = ["yamux", "mplex"])
    fun `sequential and concurrent reuse opens no streams and writes no frames`(muxer: String) {
        val opensA = AtomicInteger()
        val opensB = AtomicInteger()
        val framesA = AtomicInteger()
        val framesB = AtomicInteger()
        val a = wireCountedHost(muxer, opensA, framesA)
        val b = wireCountedHost(muxer, opensB, framesB)
        val inbound = CompletableFuture<Connection>()
        val parents = CopyOnWriteArrayList<Connection>()
        val children = CopyOnWriteArrayList<CompletableFuture<Unit>>()
        val childrenA = LinkedBlockingQueue<Stream>()
        val childrenB = LinkedBlockingQueue<Stream>()
        a.addConnectionHandler(ConnectionHandler.create { parents.add(it) })
        b.addConnectionHandler(
            ConnectionHandler.create {
                parents.add(it)
                inbound.complete(it)
            }
        )
        a.addStreamVisitor(
            ChannelVisitor {
                children.add(it.closeFuture())
                childrenA.add(it)
            }
        )
        b.addStreamVisitor(
            ChannelVisitor {
                children.add(it.closeFuture())
                childrenB.add(it)
            }
        )
        val callers = Executors.newFixedThreadPool(32)
        try {
            a.start().get(10, TimeUnit.SECONDS)
            b.start().get(10, TimeUnit.SECONDS)
            val outbound = a.network.connect(b.peerId, b.listenAddresses().single()).get(10, TimeUnit.SECONDS)
            val remote = inbound.get(10, TimeUnit.SECONDS)
            assertThat(outbound.localAddress()).isEqualTo(remote.remoteAddress())
            assertThat(outbound.remoteAddress()).isEqualTo(remote.localAddress())

            val beforeEcho = listOf(opensA.get(), opensB.get(), framesA.get(), framesB.get())
            // Real Echo exchanges in both directions prove that both instruments see real work.
            countedEcho(a, outbound, childrenA, childrenB, "before reuse from a")
            countedEcho(b, remote, childrenB, childrenA, "before reuse from b")
            wireBarrier(outbound)
            wireBarrier(remote)
            val before = listOf(opensA.get(), opensB.get(), framesA.get(), framesB.get())
            before.zip(beforeEcho).forEach { (afterEcho, initial) ->
                assertThat(afterEcho).describedAs("Real Echo must exercise each instrument").isGreaterThan(initial)
            }

            // Sequential reuse is a separate public contract from simultaneous calls.
            repeat(32) { index ->
                val resultA = if (index % 2 == 0) {
                    a.network.connect(b.peerId)
                } else {
                    a.network.connect(b.peerId, b.listenAddresses().single())
                }
                val resultB = if (index % 2 == 0) {
                    b.network.connect(a.peerId)
                } else {
                    b.network.connect(a.peerId, outbound.localAddress())
                }
                assertThat(resultA.get(10, TimeUnit.SECONDS)).isSameAs(outbound)
                assertThat(resultB.get(10, TimeUnit.SECONDS)).isSameAs(remote)
            }
            val start = CompletableFuture<Unit>()
            val results = (0 until 32).map { index ->
                CompletableFuture.supplyAsync({
                    start.get(10, TimeUnit.SECONDS)
                    if (index % 2 == 0) {
                        a.network.connect(b.peerId).get(10, TimeUnit.SECONDS).also {
                            assertThat(it).isSameAs(outbound)
                        }
                    } else {
                        b.network.connect(a.peerId, outbound.localAddress()).get(10, TimeUnit.SECONDS).also {
                            assertThat(it).isSameAs(remote)
                        }
                    }
                }, callers)
            }
            start.complete(Unit)
            CompletableFuture.allOf(*results.toTypedArray()).get(10, TimeUnit.SECONDS)
            wireBarrier(outbound)
            wireBarrier(remote)
            assertThat(listOf(opensA.get(), opensB.get(), framesA.get(), framesB.get()))
                .describedAs("Reuse must leave both session-open counts and both outbound-frame counts unchanged")
                .isEqualTo(before)
            assertThat(a.network.connections).containsExactly(outbound)
            assertThat(b.network.connections).containsExactly(remote)
            assertThat(outbound.closeFuture().isDone).isFalse()
            assertThat(remote.closeFuture().isDone).isFalse()
            countedEcho(a, outbound, childrenA, childrenB, "complete Echo after reuse")
        } finally {
            try {
                callers.shutdownNow()
                assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
            } finally {
                try {
                    a.stop().get(10, TimeUnit.SECONDS)
                } finally {
                    b.stop().get(10, TimeUnit.SECONDS)
                }
            }
            parents.forEach {
                it.closeFuture().get(10, TimeUnit.SECONDS)
                assertThat(it.closeFuture().isDone).isTrue()
            }
            children.forEach {
                it.get(10, TimeUnit.SECONDS)
                assertThat(it.isDone).isTrue()
            }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private fun wireCountedHost(muxer: String, opens: AtomicInteger, frames: AtomicInteger): Host = host {
    network { listen("/ip4/127.0.0.1/tcp/0") }
    secureChannels { add(::NoiseXXSecureChannel) }
    muxers {
        add(
            StreamMuxerProtocol { multistream, protocols ->
                val real = (if (muxer == "yamux") StreamMuxerProtocol.getYamux() else StreamMuxerProtocol.Mplex)
                    .createMuxer(multistream, protocols)
                (real as StreamMuxerDebug).muxFramesDebugHandler = ChannelVisitor { connection ->
                    connection.pushHandler(object : ChannelOutboundHandlerAdapter() {
                        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                            frames.incrementAndGet()
                            ctx.write(msg, promise)
                        }
                    })
                }
                object : StreamMuxer by real {
                    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> =
                        real.initChannel(ch, selectedProtocol).thenApply { session ->
                            object : StreamMuxer.Session by session {
                                override fun <T> createStream(protocol: ProtocolBinding<T>): StreamPromise<T> =
                                    createStream(listOf(protocol))

                                override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
                                    opens.incrementAndGet()
                                    return session.createStream(protocols)
                                }
                            }
                        }
                }
            }
        )
    }
    protocols { add(Echo()) }
}

private fun countedEcho(
    sender: Host,
    parent: Connection,
    localChildren: LinkedBlockingQueue<Stream>,
    remoteChildren: LinkedBlockingQueue<Stream>,
    payload: String
) {
    val promise = sender.newStream<EchoController>(listOf("/test/echo"), parent)
    val local = promise.stream.get(10, TimeUnit.SECONDS)
    assertThat(localChildren.poll(10, TimeUnit.SECONDS)).isSameAs(local)
    assertThat(promise.controller.get(10, TimeUnit.SECONDS).echo(payload).get(10, TimeUnit.SECONDS)).isEqualTo(payload)
    val remote = remoteChildren.poll(10, TimeUnit.SECONDS)
    assertThat(remote).isNotNull()
    local.close().get(10, TimeUnit.SECONDS)
    remote.closeFuture().get(10, TimeUnit.SECONDS)
}

private fun wireBarrier(parent: Connection) {
    (parent as P2PChannelOverNetty).nettyChannel.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
}
