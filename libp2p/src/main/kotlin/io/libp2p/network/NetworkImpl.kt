package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Network
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.TransportNotSupportedException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.transport.Transport
import io.libp2p.etc.types.anyComplete
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class NetworkImpl(
    override val transports: List<Transport>,
    override val connectionHandler: ConnectionHandler
) : Network {

    /**
     * The connection table.
     */
    override val connections = CopyOnWriteArrayList<Connection>()

    override fun reserveConnection(connection: Connection): CompletableFuture<Boolean> =
        if (connectionEligible(connection)) {
            io.libp2p.network.reserveConnection(connection)
        } else {
            CompletableFuture.completedFuture(false)
        }

    private val pendingDials = ConcurrentHashMap<PendingDialKey, CompletableFuture<Connection>>()

    /**
     * The connection [connect] settled on for each peer, so that every caller of [connect] — and
     * every address raced within one call — converges on the same one. See
     * [retainOneConnectionPerPeer].
     */
    private val settledConnections = ConcurrentHashMap<PeerId, Connection>()

    init {
        transports.forEach(Transport::initialize)
    }

    override fun close(): CompletableFuture<Unit> {
        val transportsClosed = transports.map(Transport::close)
        val connectionsClosed = connections.map(Connection::close)

        val everythingThatNeedsToClose = transportsClosed.union(connectionsClosed)

        return if (everythingThatNeedsToClose.isNotEmpty()) {
            CompletableFuture.allOf(*everythingThatNeedsToClose.toTypedArray()).thenApply { }
        } else {
            CompletableFuture.completedFuture(Unit)
        }
    }

    override fun listen(addr: Multiaddr, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Unit> =
        getTransport(addr).listen(addr, createHookedConnHandler(connectionHandler), preHandler)
    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> = getTransport(addr).unlisten(addr)
    override fun disconnect(conn: Connection): CompletableFuture<Unit> =
        conn.close()

    private fun getTransport(addr: Multiaddr) =
        transports.firstOrNull { tpt -> tpt.handles(addr) }
            ?: throw TransportNotSupportedException("no transport to handle addr: $addr")

    /**
     * Records [conn] in the connection table before handing it to [handler].
     *
     * The connection table is what [close], [disconnect] and [findActiveConnection] all work
     * from, so a live connection missing from it can never be closed by its owner and can never
     * be reused by the next dial to the same peer — every further `connect()` opens another
     * socket that nothing tracks and nothing closes.
     *
     * [handler] is the application's connection handler chain: a public extension point
     * (`Host.addConnectionHandler`, and any [ConnectionHandler] a protocol binding implements)
     * whose failures are not this class's to control. Broadcasting to it first, as this used to,
     * put the network's own bookkeeping behind it in a
     * [io.libp2p.etc.BroadcastConnectionHandler] that dispatches with a bare `forEach` — so the
     * first handler that threw stopped the broadcast before the registration below ever ran. The
     * connection is fully established by then (secure session and muxer are both attached before
     * [io.libp2p.transport.implementation.ConnectionBuilder.initChannel] calls this), and its
     * socket stays open, so the failure stranded a live connection outside the network.
     *
     * Registering first makes the bookkeeping unconditional. A handler failure still propagates
     * exactly as before — it completes the dial exceptionally — but it now leaves behind a
     * connection the caller can find and close.
     */
    private fun createHookedConnHandler(handler: ConnectionHandler) =
        ConnectionHandler.create { conn ->
            connections += conn
            conn.closeFuture().thenAccept { connections -= conn }
            handler.handleConnection(conn)
        }

    /**
     * Connects to a peerid with a provided set of {@code Multiaddr}, returning the existing connection if already connected.
     */
    override fun connect(id: PeerId, preHandler: ChannelVisitor<P2PChannel>?, vararg addrs: Multiaddr): CompletableFuture<Connection> {
        // we already have a connection for this peer, short circuit.
        findActiveConnection(id)
            ?.apply {
                return thenCompose { connection ->
                    if (connection != null) CompletableFuture.completedFuture(connection) else connect(id, preHandler, *addrs)
                }
            }

        val connectionFuts = addrs.map { it.withP2P(id) }
            .mapNotNull { addr ->
                transports.firstOrNull { transport -> transport.handles(addr) }
                    ?.let { transport ->
                        pendingDial(id, addr, transport, preHandler)
                            .thenCompose { connection -> retainOneConnectionPerPeer(id, connection) }
                            .thenCompose { selected ->
                                if (selected != null) CompletableFuture.completedFuture(selected) else connect(id, preHandler, *addrs)
                            }
                    }
            }
        return anyComplete(connectionFuts)
    }

    /**
     * Keeps one local preference, while preserving physical parents already retained at either end.
     * A surplus dial is retired only after both endpoints exclude it from future selection.
     */
    private fun retainOneConnectionPerPeer(id: PeerId, connection: Connection): CompletableFuture<Connection?> {
        var newlySettled = false
        val settled = settledConnections.compute(id) { _, alreadySettled ->
            when {
                alreadySettled != null && connectionEligible(alreadySettled) -> alreadySettled
                connectionEligible(connection) -> {
                    newlySettled = true
                    connection
                }
                else -> null
            }
        }
        if (settled !== connection) rejectDuplicate(connection)
        // A callback may explicitly close its own fresh dial. That is a terminal dial result,
        // not a duplicate-selection rejection: returning it must not start an endless redial.
        if (settled == null) return CompletableFuture.completedFuture(if (duplicateRetired(connection)) null else connection)
        // Do not start wire operations or invoke callbacks inside the peer-map computation.
        if (newlySettled) settled.closeFuture().thenAccept { settledConnections.remove(id, settled) }
        return io.libp2p.network.reserveConnection(settled).handle { accepted, failure ->
            if (failure != null || !accepted) settledConnections.remove(id, settled)
            if (failure != null) throw java.util.concurrent.CompletionException(failure)
            if (accepted || (settled === connection && !duplicateRetired(connection))) settled else null
        }
    }

    private fun pendingDial(
        id: PeerId,
        addr: Multiaddr,
        transport: Transport,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Connection> {
        val transportAddress = Multiaddr(addr.components.filterNot { it.protocol in Protocol.PEER_ID_PROTOCOLS })
        val key = PendingDialKey(id, transportAddress, preHandler)
        val newPendingDial = CompletableFuture<Connection>()
        pendingDials.putIfAbsent(key, newPendingDial)
            ?.apply { return subscriberFuture(this) }

        // An inbound or differently-addressed outbound connection may have completed between the
        // caller's established-connection check and installing this address-level pending dial.
        val existing = findActiveConnection(id)
        if (existing != null) {
            existing.whenComplete { connection, error ->
                when {
                    error != null -> completePendingDial(key, newPendingDial, null, error)
                    connection != null -> completePendingDial(key, newPendingDial, connection, null)
                    else -> startTransportDial(key, newPendingDial, addr, transport, preHandler)
                }
            }
        } else {
            startTransportDial(key, newPendingDial, addr, transport, preHandler)
        }
        return subscriberFuture(newPendingDial)
    }

    private fun startTransportDial(
        key: PendingDialKey,
        newPendingDial: CompletableFuture<Connection>,
        addr: Multiaddr,
        transport: Transport,
        preHandler: ChannelVisitor<P2PChannel>?
    ) {
        try {
            transport.dial(addr, createHookedConnHandler(connectionHandler), preHandler)
                .whenComplete { connection, error ->
                    completePendingDial(key, newPendingDial, connection, error)
                }
        } catch (error: Exception) {
            completePendingDial(key, newPendingDial, null, error)
        }
    }

    private fun findActiveConnection(id: PeerId): CompletableFuture<Connection?>? {
        settledConnections[id]?.takeIf { connectionEligible(it) }
            ?.let { return retainOneConnectionPerPeer(id, it) }

        connections.forEach { connection ->
            if (connection.closeFuture().isDone) {
                connections.remove(connection)
            } else if (connectionEligible(connection) && connection.secureSession().remoteId == id) {
                // The connection hook runs before the transport dial future completes. Reserve
                // the selected parent now so a later dial result cannot close a caller's parent.
                return retainOneConnectionPerPeer(id, connection)
            }
        }
        return null
    }

    private fun subscriberFuture(pendingDial: CompletableFuture<Connection>): CompletableFuture<Connection> =
        pendingDial.thenApply { it }

    private fun completePendingDial(
        key: PendingDialKey,
        pendingDial: CompletableFuture<Connection>,
        connection: Connection?,
        error: Throwable?
    ) {
        pendingDials.remove(key, pendingDial)
        if (error == null) {
            pendingDial.complete(connection!!)
        } else {
            pendingDial.completeExceptionally(error)
        }
    }

    /**
     * [address] contains transport components only: [peerId] represents peer identity independently,
     * so equivalent `/ipfs/` and `/p2p/` spellings share a pending dial.
     *
     * [preHandler] is part of the key because a raw transport channel can invoke only the handler
     * supplied to its own dial. Distinct handler instances therefore never silently share a dial;
     * null, the same instance, or an explicitly equal implementation may share safely.
     */
    private data class PendingDialKey(
        val peerId: PeerId,
        val address: Multiaddr,
        val preHandler: ChannelVisitor<P2PChannel>?
    )
}
