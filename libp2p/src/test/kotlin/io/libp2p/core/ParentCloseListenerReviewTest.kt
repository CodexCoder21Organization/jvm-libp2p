@file:Suppress("DEPRECATION")

package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.types.toCompletableFuture
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.implementation.ConnectionBuilder
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelFuture
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class ParentCloseListenerReviewTest {
    @Test
    fun mplexVisitorClosedChildrenReleaseParentCloseListeners() {
        reviewParentCloseListeners(StreamMuxerProtocol.Mplex)
    }

    @Test
    fun yamuxVisitorClosedChildrenReleaseParentCloseListeners() {
        reviewParentCloseListeners(StreamMuxerProtocol.getYamux())
    }
}

private fun reviewParentCloseListeners(muxer: StreamMuxerProtocol) {
    val client = host {
        transports { add { upgrader -> ListenerCountingTcpTransport(upgrader) } }
        muxers { +muxer }
        protocols { +Ping() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    val server = host {
        muxers { +muxer }
        protocols { +Ping() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    try {
        client.start().get(5, TimeUnit.SECONDS)
        server.start().get(5, TimeUnit.SECONDS)
        val parent = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
            .get(5, TimeUnit.SECONDS)
        val channel = (parent as P2PChannelOverNetty).nettyChannel as ListenerCountingSocketChannel
        channel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
        val baseline = channel.observedCloseFuture.listenerCount()
        val visitor = ChannelVisitor<Stream> { stream ->
            if (stream.isInitiator && stream.connection === parent) stream.reset()
        }
        client.addStreamVisitor(visitor)
        try {
            // Each later cycle observes the listener residue left by its predecessors on the same parent.
            repeat(128) {
                val promise = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols, parent)
                val stream = promise.stream.get(5, TimeUnit.SECONDS)
                stream.closeFuture().get(5, TimeUnit.SECONDS)
                val expectedMessage = "Channel closed before protocol negotiation: $stream"
                val controllerFailure = assertThrows(ExecutionException::class.java) {
                    promise.controller.get(5, TimeUnit.SECONDS)
                }
                val protocolFailure = assertThrows(ExecutionException::class.java) {
                    stream.getProtocol().get(5, TimeUnit.SECONDS)
                }
                assertEquals(ConnectionClosedException::class.java, controllerFailure.cause!!.javaClass)
                assertEquals(expectedMessage, controllerFailure.cause!!.message)
                assertEquals(ConnectionClosedException::class.java, protocolFailure.cause!!.javaClass)
                assertEquals(expectedMessage, protocolFailure.cause!!.message)
            }
            channel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
            assertFalse(parent.closeFuture().isDone)
            assertTrue(client.streams.isEmpty(), "All visitor-closed streams must be released by Host")
            assertEquals(baseline, channel.observedCloseFuture.listenerCount(),
                "128 closed children must leave no parent-close listener behind on their live connection")
        } finally {
            client.removeStreamVisitor(visitor)
        }
    } finally {
        try { client.stop().get(5, TimeUnit.SECONDS) } finally { server.stop().get(5, TimeUnit.SECONDS) }
    }
}

// This is a real TCP transport and real Netty socket. Only listener registrations are counted;
// the delegate performs every future operation and the ordinary ConnectionBuilder performs upgrades.
private class ListenerCountingTcpTransport(private val connectionUpgrader: ConnectionUpgrader) :
    TcpTransport(connectionUpgrader) {
    private val group = NioEventLoopGroup(1)
    private val sockets = CopyOnWriteArrayList<ListenerCountingSocketChannel>()

    override fun dial(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Connection> {
        val builder = ConnectionBuilder(this, connectionUpgrader, connHandler, true, addr.getPeerId(), preHandler)
        return Bootstrap().group(group)
            .channelFactory(ChannelFactory {
                ListenerCountingSocketChannel().also { sockets += it }
            })
            .handler(builder)
            .connect(InetSocketAddress(hostFromMultiaddr(addr), portFromMultiaddr(addr)))
            .toCompletableFuture().thenCompose { builder.connectionEstablished }
    }

    override fun close(): CompletableFuture<Unit> {
        val closeOperations = sockets.map { it.close().toCompletableFuture() } + super.close()
        return CompletableFuture.allOf(*closeOperations.toTypedArray()).thenCompose {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).toCompletableFuture().thenApply { Unit }
        }
    }
}

private class ListenerCountingSocketChannel : NioSocketChannel() {
    val observedCloseFuture: ListenerCountingChannelFuture by lazy {
        ListenerCountingChannelFuture(super.closeFuture())
    }
    override fun closeFuture(): ChannelFuture = observedCloseFuture
}

private class ListenerCountingChannelFuture(private val actual: ChannelFuture) : ChannelFuture by actual {
    private val registered = Collections.synchronizedSet(
        Collections.newSetFromMap(IdentityHashMap<GenericFutureListener<*>, Boolean>())
    )

    init {
        actual.addListener { registered.clear() }
    }

    fun listenerCount(): Int = registered.size

    override fun addListener(listener: GenericFutureListener<out Future<in Void>>): ChannelFuture {
        registered.add(listener)
        actual.addListener(listener)
        return this
    }

    override fun removeListener(listener: GenericFutureListener<out Future<in Void>>): ChannelFuture {
        registered.remove(listener)
        actual.removeListener(listener)
        return this
    }
}
