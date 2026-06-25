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
import io.netty.util.concurrent.DefaultThreadFactory
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
    // `closed` is read in listen()/dial()/registerChannel() and written in close().
    // Made @Volatile to pair with the `synchronized(this@PlainNettyTransport)` blocks
    // that read it under-lock — see the comment on close() for why both are needed.
    @Volatile
    private var closed = false
    var connectTimeout = Duration.ofSeconds(15)

    // `listeners` and `channels` are mutated by listen()/dial() under
    // `synchronized(this@PlainNettyTransport)`. close() must acquire the SAME
    // monitor before reading either map — see close() for the race that
    // motivated this. Holding the monitor while iterating also guarantees we
    // are reading a snapshot rather than a concurrently-mutated collection.
    private val listeners = mutableMapOf<Multiaddr, Channel>()
    private val channels = mutableListOf<Channel>()

    // DAEMON event-loop threads. Netty's MultiThreadIoEventLoopGroup, when constructed without a
    // ThreadFactory, uses DefaultThreadFactory with daemon=false, so every NIO worker is a NON-DAEMON
    // thread. Under load, shutdownGracefully() occasionally does not get one of these workers fully
    // terminated before close() returns (the shutdown task does not get scheduled in time), and a single
    // surviving non-daemon thread parked in epoll_wait blocks JVM exit indefinitely — the consumer-side
    // symptom is a test/process that finishes its work but then "Process timed out after 30s" with the
    // event loop never having exited. Marking the workers daemon makes that failure mode impossible: a
    // missed/slow shutdown can no longer hold the JVM open. (GossipRouter's event thread is already
    // daemon for the same reason — see GossipRouterBuilder.)
    private var workerGroup by lazyVar {
        MultiThreadIoEventLoopGroup(DefaultThreadFactory("libp2p-nio-worker", true), NioIoHandler.newFactory())
    }
    private var bossGroup by lazyVar {
        MultiThreadIoEventLoopGroup(1, DefaultThreadFactory("libp2p-nio-boss", true), NioIoHandler.newFactory())
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
        // Take a consistent snapshot of the listener and child-channel collections
        // under the same monitor that listen() / dial() / registerChannel() use to
        // mutate them.
        //
        // Prior to this synchronization close() read `listeners` and `channels`
        // without holding the transport monitor. listen() writes to `listeners`
        // inside `synchronized(this@PlainNettyTransport)` AFTER calling
        // `listener.bind(addr)` — the bind task is already submitted to the boss
        // event loop at this point (so the port will be bound), but the map
        // update has not yet happened. A concurrent close() in that microsecond
        // window observed an empty map, scheduled no channel close, and proceeded
        // straight to `shutdownGracefully(0, 5s)`. Netty's event loop still ran
        // the queued bind task before terminating (so the port DID get bound),
        // but the channel was never explicitly closed — leaving the OS file
        // descriptor open for the lifetime of the test JVM. Downstream this
        // surfaced as repeated `UrlProtocol2.close(): listen port N was not
        // released within 2 seconds of host.stop()` in UrlResolver's
        // stressTestCloseReleasesPortForDaemonOnlyInstanceUnderLoad —
        // https://buildtest.kotlin.build/run?id=4fe5b865 .
        //
        // Setting `closed = true` inside the synchronized block also pairs with
        // the closed-check that listen() now performs INSIDE its own synchronized
        // block: any listen() that arrives after this close()'s sync acquire is
        // guaranteed to observe `closed = true` and reject the bind, so no new
        // listener can slip in during the shutdownGracefully phase below.
        val listenersToClose: List<Channel>
        val channelsToClose: List<Channel>
        synchronized(this@PlainNettyTransport) {
            closed = true
            listenersToClose = listeners.values.toList()
            channelsToClose = channels.toList()
        }

        val unbindsCompleted = listenersToClose
            .map { it.close().toVoidCompletableFuture() }

        val channelsClosed = channelsToClose
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
        // Hold the transport monitor for the entire `closed`-check ->
        // `listener.bind(addr)` -> `listeners += ...` sequence. Splitting these
        // across the lock boundary (as the prior shape did) opens a race window:
        // after bind() returns, the bind task is already on the boss event loop
        // queue (so the port WILL be bound when the event loop drains), but the
        // listener channel is not yet registered in `listeners`. A concurrent
        // close() that reads `listeners` in that window observes an empty map,
        // closes no listener channel, and proceeds straight to
        // shutdownGracefully — which still runs the queued bind task before the
        // event loop terminates. The result is a permanently-bound port with no
        // owning channel to close. See close() for the matching read-under-lock
        // and the regression test PlainNettyTransportConcurrentListenCloseTest
        // for the deterministic reproduction.
        val bindComplete = synchronized(this@PlainNettyTransport) {
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

            val cf = listener.bind(fromMultiaddr(addr))
            listeners += addr to cf.channel()
            cf.channel().closeFuture().addListener {
                synchronized(this@PlainNettyTransport) {
                    listeners -= addr
                }
            }
            cf
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
        // Perform the closed-check and the map mutation atomically. If we
        // checked `closed` outside the monitor and then acquired it, a
        // concurrent close() could observe `closed = true` and snapshot the
        // `channels` collection in the window between the check and the add,
        // missing this channel entirely — same shape as the listen() race
        // documented on close(). Doing both under one acquire makes that
        // window unrepresentable.
        synchronized(this@PlainNettyTransport) {
            if (closed) {
                ch.close()
                return
            }

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
