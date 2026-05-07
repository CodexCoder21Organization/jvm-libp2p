package io.libp2p.transport.implementation

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrDns
import io.libp2p.core.multiformats.Protocol
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toCompletableFuture
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.transport.ConnectionUpgrader
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A plain `NettyTransport` without embedded security and muxer
 */
abstract class PlainNettyTransport(
    private val upgrader: ConnectionUpgrader
) : NettyTransport { // class NettyTransportBase
    private var closed = false
    var connectTimeout = Duration.ofSeconds(15)

    private val listeners = mutableMapOf<Multiaddr, Channel>()
    private val channels = mutableListOf<Channel>()

    private var workerGroup by lazyVar {
        MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
    }
    private var bossGroup by lazyVar {
        MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    }

    private var client by lazyVar {
        Bootstrap().apply {
            group(workerGroup)
            channel(NioSocketChannel::class.java)
            option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
        }
    }

    private var server by lazyVar {
        ServerBootstrap().apply {
            group(bossGroup, workerGroup)
            channel(NioServerSocketChannel::class.java)
        }
    }

    override val activeListeners: Int
        get() = listeners.size
    override val activeConnections: Int
        get() = channels.size

    override fun listenAddresses(): List<Multiaddr> {
        return listeners.values.map {
            toMultiaddr(it.localAddress() as InetSocketAddress)
        }
    }

    override fun initialize() {
    }

    override fun close(): CompletableFuture<Unit> {
        closed = true

        val unbindsCompleted = listeners
            .map { (_, ch) -> ch }
            .map { it.close().toVoidCompletableFuture() }

        val channelsClosed = channels
            .toMutableList() // need a copy to avoid potential co-modification problems
            .map { it.close().toVoidCompletableFuture() }

        val everythingThatNeedsToClose = unbindsCompleted.union(channelsClosed)
        val allClosed = CompletableFuture.allOf(*everythingThatNeedsToClose.toTypedArray())

        return allClosed.thenCompose {
            // Use an explicit quietPeriod = 0 instead of relying on Netty's default
            // (DefaultEventExecutor.DEFAULT_SHUTDOWN_QUIET_PERIOD = 2 SECONDS).
            //
            // The quiet period is the time `shutdownGracefully` keeps the event loop
            // alive AFTER its last task finished, in case more work is submitted. Once
            // we have called close() on this transport every existing channel has been
            // closed, the transport is marked closed (so listen()/dial() throw), and
            // there is by construction no path that submits new work to either group.
            // Waiting 2 seconds for "more work that isn't coming" is pure latency.
            //
            // For callers that drive many short-lived host lifecycles back-to-back —
            // for example test fixtures that create-and-tear-down a host per iteration —
            // the default 2-second quiet period serializes into a per-iteration cost
            // and any @Timeout-bounded test in the consumer fails after only a handful
            // of iterations even though every individual close() is correct.
            //
            // The 5-second timeout caps how long shutdownGracefully will wait for the
            // event loop to actually exit (vs. how long it will idle waiting for more
            // work). That's the meaningful upper bound on close() latency. The future
            // returned by shutdownGracefully resolves either when the loop exits or
            // when the timeout elapses, so callers that need to wait for full Netty
            // termination still get that signal — just without the artificial 2-second
            // quiet floor.
            //
            // See PlainNettyTransportShutdownTimingTest for the regression coverage,
            // and the discussion in the original CodexCoder21Organization/UrlResolver
            // run 7f19e875 that surfaced the cumulative latency.
            CompletableFuture.allOf(
                workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).toVoidCompletableFuture(),
                bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).toVoidCompletableFuture()
            ).thenApply { }
        }
    } // close

    override fun listen(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Unit> {
        if (closed) throw Libp2pException("Transport is closed")

        val connectionBuilder = makeConnectionBuilder(connHandler, false, preHandler = preHandler)
        val channelHandler = serverTransportBuilder(connectionBuilder, addr) ?: connectionBuilder

        val listener = server.clone()
            .childHandler(
                nettyInitializer { init ->
                    registerChannel(init.channel)
                    init.addLastLocal(channelHandler)
                }
            )

        val bindComplete = listener.bind(fromMultiaddr(addr))

        bindComplete.also {
            synchronized(this@PlainNettyTransport) {
                listeners += addr to it.channel()
                it.channel().closeFuture().addListener {
                    synchronized(this@PlainNettyTransport) {
                        listeners -= addr
                    }
                }
            }
        }

        return bindComplete.toVoidCompletableFuture()
    } // listener

    protected abstract fun serverTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler?

    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        return listeners[addr]?.close()?.toVoidCompletableFuture()
            ?: throw Libp2pException("No listeners on address $addr")
    } // unlisten

    override fun dial(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")

        val remotePeerId = addr.getPeerId()
        val connectionBuilder = makeConnectionBuilder(connHandler, true, remotePeerId, preHandler)
        val channelHandler = clientTransportBuilder(connectionBuilder, addr) ?: connectionBuilder

        val chanFuture = client.clone()
            .handler(channelHandler)
            .connect(fromMultiaddr(addr))
            .also { registerChannel(it.channel()) }

        return chanFuture.toCompletableFuture()
            .thenCompose { connectionBuilder.connectionEstablished }
    } // dial

    protected abstract fun clientTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler?

    private fun registerChannel(ch: Channel) {
        if (closed) {
            ch.close()
            return
        }

        synchronized(this@PlainNettyTransport) {
            channels += ch
            ch.closeFuture().addListener {
                synchronized(this@PlainNettyTransport) {
                    channels -= ch
                }
            }
        }
    } // registerChannel

    private fun makeConnectionBuilder(
        connHandler: ConnectionHandler,
        initiator: Boolean,
        remotePeerId: PeerId? = null,
        preHandler: ChannelVisitor<P2PChannel>?
    ) = ConnectionBuilder(
        this,
        upgrader,
        connHandler,
        initiator,
        remotePeerId,
        preHandler
    )

    protected fun handlesHost(addr: Multiaddr) =
        addr.hasAny(Protocol.IP4, Protocol.IP6, Protocol.DNS4, Protocol.DNS6, Protocol.DNSADDR)

    protected fun hostFromMultiaddr(addr: Multiaddr): String {
        val resolvedAddresses = MultiaddrDns.resolve(addr)
        if (resolvedAddresses.isEmpty()) {
            throw Libp2pException("Could not resolve $addr to an IP address")
        }

        return resolvedAddresses[0].components.find {
            it.protocol in arrayOf(Protocol.IP4, Protocol.IP6)
        }?.stringValue ?: throw Libp2pException("Missing IP4/IP6 in multiaddress $addr")
    }

    protected fun portFromMultiaddr(addr: Multiaddr) =
        addr.components.find { p -> p.protocol == Protocol.TCP }
            ?.stringValue?.toInt() ?: throw Libp2pException("Missing TCP in multiaddress $addr")

    private fun fromMultiaddr(addr: Multiaddr): InetSocketAddress {
        val host = hostFromMultiaddr(addr)
        val port = portFromMultiaddr(addr)
        return InetSocketAddress(host, port)
    } // fromMultiaddr

    override fun localAddress(nettyChannel: Channel): Multiaddr = toMultiaddr(nettyChannel.localAddress())
    override fun remoteAddress(nettyChannel: Channel): Multiaddr = toMultiaddr(nettyChannel.remoteAddress())

    abstract fun toMultiaddr(addr: SocketAddress): Multiaddr
}
