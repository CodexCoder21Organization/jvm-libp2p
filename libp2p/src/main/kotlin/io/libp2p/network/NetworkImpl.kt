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

    private fun createHookedConnHandler(handler: ConnectionHandler) =
        ConnectionHandler.createBroadcast(
            listOf(
                handler,
                ConnectionHandler.create { conn ->
                    connections += conn
                    conn.closeFuture().thenAccept { connections -= conn }
                }
            )
        )

    /**
     * Connects to a peerid with a provided set of {@code Multiaddr}, returning the existing connection if already connected.
     */
    override fun connect(id: PeerId, preHandler: ChannelVisitor<P2PChannel>?, vararg addrs: Multiaddr): CompletableFuture<Connection> {
        // we already have a connection for this peer, short circuit.
        findActiveConnection(id)
            ?.apply { return CompletableFuture.completedFuture(this) }

        val connectionFuts = addrs.map { it.withP2P(id) }
            .mapNotNull { addr ->
                transports.firstOrNull { transport -> transport.handles(addr) }
                    ?.let { transport ->
                        pendingDial(id, addr, transport, preHandler)
                            .thenApply { connection -> retainOneConnectionPerPeer(id, connection) }
                    }
            }
        return anyComplete(connectionFuts)
    }

    /**
     * Returns the connection this network has settled on for [id], closing [connection] when that
     * is a different one.
     *
     * A peer is reachable at several addresses and [connect] dials all of them at once, so several
     * dials can succeed. Only one connection is ever returned to a caller; without this, each of
     * the others stayed fully established — a live socket, Netty pipeline and muxer session that no
     * caller holds a reference to and nothing ever closes. A long-lived process dialling a large,
     * churning peer population retained one such connection per surplus address per peer it had
     * ever contacted.
     *
     * The winner is chosen by a single atomic map operation rather than by dial completion order,
     * so concurrent callers — which share the same pending dials but race their own address lists —
     * always converge on the same connection instead of each closing the one another is using. A
     * connection that has already closed does not keep its peer's slot.
     */
    private fun retainOneConnectionPerPeer(id: PeerId, connection: Connection): Connection {
        // The remapping function never returns null, so neither does compute; the elvis branch is
        // unreachable and exists only to keep the result non-nullable without an unsafe call.
        val settled = settledConnections.compute(id) { _, alreadySettled ->
            if (alreadySettled != null && !alreadySettled.closeFuture().isDone) alreadySettled else connection
        } ?: connection
        if (settled !== connection) {
            connection.close()
        } else {
            // Releasing the slot on close lets the next connect() to this peer settle afresh.
            // Registering this more than once for the same connection is harmless: the removal is
            // conditional on the mapping still being this connection, and repeating it is a no-op.
            connection.closeFuture().thenAccept { settledConnections.remove(id, connection) }
        }
        return settled
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
        findActiveConnection(id)
            ?.also {
                completePendingDial(key, newPendingDial, it, null)
                return subscriberFuture(newPendingDial)
            }

        try {
            transport.dial(addr, createHookedConnHandler(connectionHandler), preHandler)
                .whenComplete { connection, error ->
                    completePendingDial(key, newPendingDial, connection, error)
                }
        } catch (error: Exception) {
            completePendingDial(key, newPendingDial, null, error)
        }

        return subscriberFuture(newPendingDial)
    }

    private fun findActiveConnection(id: PeerId): Connection? {
        connections.forEach { connection ->
            if (connection.closeFuture().isDone) {
                connections.remove(connection)
            } else if (connection.secureSession().remoteId == id) {
                return connection
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
