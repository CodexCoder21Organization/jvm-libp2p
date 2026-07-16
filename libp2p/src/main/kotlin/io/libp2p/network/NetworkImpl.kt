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
        connections.find { it.secureSession().remoteId == id }
            ?.apply { return CompletableFuture.completedFuture(this) }

        val connectionFuts = addrs.map { it.withP2P(id) }
            .mapNotNull { addr ->
                transports.firstOrNull { transport -> transport.handles(addr) }
                    ?.let { transport -> pendingDial(id, addr, transport, preHandler) }
            }
        return anyComplete(connectionFuts)
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
        connections.find { it.secureSession().remoteId == id }
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
