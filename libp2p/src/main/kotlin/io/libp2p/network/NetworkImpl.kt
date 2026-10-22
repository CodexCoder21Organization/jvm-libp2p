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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class NetworkImpl(
    override val transports: List<Transport>,
    override val connectionHandler: ConnectionHandler
) : Network {

    /**
     * The connection table.
     */
    override val connections = CopyOnWriteArrayList<Connection>()

    private val pendingDials = ConcurrentHashMap<PendingDialKey, PendingDial>()

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
            ?.apply { return CompletableFuture.completedFuture(retainOneConnectionPerPeer(id, this)) }

        val aggregate = CompletableFuture<Connection>()
        val subscriptions = addrs.map { it.withP2P(id) }
            .mapNotNull { addr ->
                transports.firstOrNull { transport -> transport.handles(addr) }
                    ?.let { transport -> pendingDial(id, addr, transport, preHandler, aggregate) }
            }
        aggregate.whenComplete { _, _ ->
            subscriptions.forEach { releaseDialSubscription(pendingDials, it) }
        }
        anyComplete(subscriptions.map { it.future }).whenComplete { connection, error ->
            if (error == null) aggregate.complete(connection) else aggregate.completeExceptionally(error)
        }
        return aggregate
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
        preHandler: ChannelVisitor<P2PChannel>?,
        aggregate: CompletableFuture<Connection>
    ): DialSubscription {
        val transportAddress = Multiaddr(addr.components.filterNot { it.protocol in Protocol.PEER_ID_PROTOCOLS })
        val key = PendingDialKey(id, transportAddress, preHandler)
        var startsDial = false
        var abandoned: AbandonedDial? = null
        val subscription = synchronized(pendingDials) {
            pendingDials[key]?.let { abandoned = abandonUnusedDial(pendingDials, it) }
            val pending = pendingDials.getOrPut(key) {
                startsDial = true
                PendingDial(key)
            }
            val future = pending.result.thenApply { connection ->
                if (aggregate.isDone) throw CancellationException("Connect request for peer $id already completed")
                pending.owned.set(true)
                retainOneConnectionPerPeer(id, connection)
            }
            DialSubscription(aggregate, future, pending).also { pending.subscribers += it }
        }
        cancelAbandonedDial(abandoned)
        if (!startsDial) return subscription
        val pending = subscription.pending

        // Reuse a connection that arrived while this address entry was being installed.
        findActiveConnection(id)?.also {
            completePendingDial(pending, it, null, ownsConnection = false)
            return subscription
        }

        try {
            val handler = ConnectionHandler { connection ->
                val publish = synchronized(pendingDials) {
                    if (pending.abandoned || pending.subscribers.none { !it.aggregate.isDone }) {
                        false
                    } else {
                        // Network owns a published connection before application callbacks run.
                        pending.owned.set(true)
                        true
                    }
                }
                if (publish) {
                    createHookedConnHandler(connectionHandler).handleConnection(connection)
                } else {
                    connection.close()
                }
            }
            val physical = transport.dial(addr, handler, preHandler)
            val cancel = synchronized(pendingDials) {
                pending.physical = physical
                pending.abandoned && !pending.owned.get()
            }
            physical.whenComplete { connection, error -> completePendingDial(pending, connection, error) }
            if (cancel && !physical.isDone) physical.cancel(true)
        } catch (error: Exception) {
            completePendingDial(pending, null, error)
        }
        return subscription
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

    private fun completePendingDial(
        pending: PendingDial,
        connection: Connection?,
        error: Throwable?,
        ownsConnection: Boolean = true
    ) {
        val needed = synchronized(pendingDials) {
            pendingDials.remove(pending.key, pending)
            !pending.abandoned
        }
        if (error != null) {
            pending.result.completeExceptionally(error)
        } else {
            val completed = requireNotNull(connection) {
                "Transport dial for peer ${pending.key.peerId} completed without a connection or an error"
            }
            // A callback may publish a connection before its physical dial future completes.
            // Settle it even when its original caller left during that callback.
            val selected = if (pending.owned.get()) {
                retainOneConnectionPerPeer(pending.key.peerId, completed)
            } else {
                completed
            }
            if (needed) pending.result.complete(selected)
            if (ownsConnection && !pending.owned.get()) completed.close()
        }
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

private class PendingDial(val key: PendingDialKey) {
    val result = CompletableFuture<Connection>()
    val subscribers = mutableSetOf<DialSubscription>()
    val owned = AtomicBoolean()
    var physical: CompletableFuture<Connection>? = null
    var abandoned = false
}

private class DialSubscription(
    val aggregate: CompletableFuture<Connection>,
    val future: CompletableFuture<Connection>,
    val pending: PendingDial
)

private class AbandonedDial(
    val result: CompletableFuture<Connection>,
    val physical: CompletableFuture<Connection>?
)

// Called with the pending-dial map locked; callbacks and transport cancellation run outside it.
private fun abandonUnusedDial(
    pendingDials: MutableMap<PendingDialKey, PendingDial>,
    pending: PendingDial
): AbandonedDial? {
    if (pending.result.isDone || pending.subscribers.any { !it.aggregate.isDone }) return null
    pending.abandoned = true
    if (pendingDials[pending.key] === pending) pendingDials.remove(pending.key)
    return AbandonedDial(pending.result, if (pending.owned.get()) null else pending.physical)
}

private fun cancelAbandonedDial(abandoned: AbandonedDial?) {
    if (abandoned == null) return
    abandoned.result.cancel(false)
    abandoned.physical?.takeUnless { it.isDone }?.cancel(true)
}

private fun releaseDialSubscription(
    pendingDials: MutableMap<PendingDialKey, PendingDial>,
    subscription: DialSubscription
) {
    val abandoned = synchronized(pendingDials) {
        subscription.pending.subscribers.remove(subscription)
        abandonUnusedDial(pendingDials, subscription.pending)
    }
    subscription.future.cancel(false)
    cancelAbandonedDial(abandoned)
}
