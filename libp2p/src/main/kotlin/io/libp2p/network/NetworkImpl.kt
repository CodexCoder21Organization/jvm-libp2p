package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
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

/**
 * Default [Network] implementation.
 *
 * [localPeerId] enables rejection of a connection to the local identity before transport work.
 * Host instances built through the DSL supply this value. Direct callers that need the same check
 * must use this peer-aware constructor rather than the two-argument compatibility constructor.
 */
class NetworkImpl(
    override val transports: List<Transport>,
    override val connectionHandler: ConnectionHandler,
    private val localPeerId: PeerId?
) : Network {

    /**
     * Retains the original public JVM constructor for source and binary compatibility.
     *
     * This constructor has no local peer identity, so it cannot reject a self-identity connection
     * before transport work. Direct callers that need that behavior must pass [localPeerId] to the
     * peer-aware constructor.
     */
    constructor(
        transports: List<Transport>,
        connectionHandler: ConnectionHandler
    ) : this(transports, connectionHandler, null)

    /**
     * The connection table.
     */
    override val connections = CopyOnWriteArrayList<Connection>()

    private val pendingDials = ConcurrentHashMap<PendingDialKey, CompletableFuture<Connection>>()

    /**
     * The connection this network settled on for each peer, so inbound connections, every caller
     * of [connect], and every address raced within one call converge on the same one. See
     * [retainOneConnectionPerPeer].
     */
    private val settledConnections = ConcurrentHashMap<PeerId, Connection>()

    /** Connections arbitration has rejected but whose asynchronous close has not completed yet. */
    private val rejectedConnections = ConcurrentHashMap.newKeySet<Connection>()

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
        ConnectionHandler.create { connection ->
            try {
                handler.handleConnection(connection)
            } catch (handlerError: Throwable) {
                try {
                    connection.close()
                } catch (closeError: Throwable) {
                    handlerError.addSuppressed(closeError)
                }
                throw handlerError
            }

            connections += connection
            val remoteId = connection.secureSession().remoteId
            connection.closeFuture().whenComplete { _, _ ->
                connections -= connection
                rejectedConnections -= connection
                reconcileConnections(remoteId)
            }
            retainOneConnectionPerPeer(remoteId, connection)
        }

    /**
     * Connects to a peerid with a provided set of {@code Multiaddr}, returning the existing connection if already connected.
     */
    override fun connect(id: PeerId, preHandler: ChannelVisitor<P2PChannel>?, vararg addrs: Multiaddr): CompletableFuture<Connection> {
        if (id == localPeerId) {
            val error = Libp2pException(
                "Cannot connect to peer ${id.toBase58()} because it is the local peer ID"
            )
            return CompletableFuture<Connection>().also { it.completeExceptionally(error) }
        }

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
     * dials can succeed. Two peers can also dial each other at the same time, establishing one
     * connection in each direction. Only one connection is retained; without this, every surplus
     * connection stays fully established with its own socket, Netty pipeline and muxer session.
     *
     * The winner is chosen by a single atomic map operation rather than by dial completion order.
     * Same-direction address races keep the first active connection. When opposite directions
     * coexist, peer identity determines the direction to keep: the lower-ID peer keeps its
     * initiator connection and the higher-ID peer keeps its responder connection. Both peers
     * therefore identify the same physical connection instead of each closing the other's winner.
     * A lone connection is always accepted, and a connection that has closed does not keep its
     * peer's slot.
     */
    private fun retainOneConnectionPerPeer(id: PeerId, connection: Connection): Connection {
        val settlement = reconcileConnections(id, connection)
        if (settlement.candidateAccepted) return connection
        return settlement.settled ?: throw ConnectionClosedException(
            "Connection to peer ${id.toBase58()} could not be retained because the candidate " +
                "was rejected or closed and no active settled connection remains " +
                "(candidateCloseComplete=${connection.closeFuture().isDone}, " +
                "candidateRejected=${connection in rejectedConnections})"
        )
    }

    /**
     * Reconciles all upgraded connections to [id] in one atomic per-peer map decision.
     *
     * Rejected connections are marked before this operation returns, then closed afterwards. This
     * keeps a closing loser out of public connection selection even when its close future is still
     * pending. When a mapped inbound address candidate closes, another live inbound candidate is
     * promoted here instead of leaving the settlement table empty.
     */
    private fun reconcileConnections(id: PeerId, candidate: Connection? = null): ConnectionSettlement {
        val connectionsToClose = mutableListOf<Connection>()
        var candidateAccepted = false
        var selected: Connection? = null

        settledConnections.compute(id) { _, alreadySettled ->
            val activeCandidates = connections.filter { connection ->
                connection !in rejectedConnections &&
                    !connection.closeFuture().isDone &&
                    connection.secureSession().remoteId == id
            }
            if (activeCandidates.isEmpty()) {
                selected = null
                return@compute null
            }

            val preferredCandidates = activeCandidates.filter(::isPreferredDirection)
            val winningCandidates = if (preferredCandidates.isNotEmpty()) {
                val preferredInitiator = preferredCandidates.first().isInitiator
                activeCandidates.filter { connection -> connection.isInitiator == preferredInitiator }
            } else {
                val retainedDirection = alreadySettled
                    ?.takeIf { it in activeCandidates }
                    ?.isInitiator
                    ?: activeCandidates.first().isInitiator
                activeCandidates.filter { connection -> connection.isInitiator == retainedDirection }
            }

            val losingDirectionCandidates = activeCandidates.filterNot { it in winningCandidates }
            reject(losingDirectionCandidates, connectionsToClose)

            val existingWinner = alreadySettled?.takeIf { it in winningCandidates }
            val winner = existingWinner ?: winningCandidates.first()
            selected = winner

            // The local dialer owns same-direction outbound address races and closes its surplus
            // candidates. Inbound candidates stay available until the remote dialer chooses one.
            if (winner.isInitiator) {
                reject(winningCandidates.filterNot { it === winner }, connectionsToClose)
            }

            candidateAccepted = candidate != null &&
                candidate in winningCandidates &&
                candidate !in rejectedConnections
            selected
        }

        connectionsToClose.distinct().forEach(Connection::close)
        return ConnectionSettlement(selected, candidateAccepted)
    }

    private fun isPreferredDirection(connection: Connection): Boolean {
        val session = connection.secureSession()
        val localPeerKeepsInitiator = session.localId.toBase58() < session.remoteId.toBase58()
        return connection.isInitiator == localPeerKeepsInitiator
    }

    private fun reject(connections: List<Connection>, connectionsToClose: MutableList<Connection>) {
        connections.forEach { connection ->
            if (rejectedConnections.add(connection)) {
                connectionsToClose += connection
            }
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
        return reconcileConnections(id).settled
    }

    private data class ConnectionSettlement(
        val settled: Connection?,
        val candidateAccepted: Boolean
    )

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
