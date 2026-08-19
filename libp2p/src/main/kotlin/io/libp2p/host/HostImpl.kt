package io.libp2p.host

import io.libp2p.core.AddressBook
import io.libp2p.core.ChannelVisitor
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.Network
import io.libp2p.core.NoSuchLocalProtocolException
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.StreamPromise
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.etc.types.hasCauseOfType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class HostImpl(
    override val privKey: PrivKey,
    override val network: Network,
    override val addressBook: AddressBook,
    private val listenAddrs: List<Multiaddr>,
    private val protocolHandlers: MutableList<ProtocolBinding<Any>>,
    private val connectionHandlers: ConnectionHandler.Broadcast,
    private val streamVisitors: ChannelVisitor.Broadcast<Stream>
) : Host {

    override val peerId = PeerId.fromPubKey(privKey.publicKey())
    override val streams = CopyOnWriteArrayList<Stream>()

    private val internalStreamVisitor = ChannelVisitor<Stream> { stream ->
        streams += stream
        stream.closeFuture().thenAccept { streams -= stream }
    }

    init {
        streamVisitors += internalStreamVisitor
    }

    override fun listenAddresses(): List<Multiaddr> {
        val listening = mutableListOf<Multiaddr>()

        network.transports.forEach {
            listening.addAll(
                it.listenAddresses().map { it.withP2P(peerId) }
            )
        }

        return listening
    }

    override fun start(): CompletableFuture<Void> {
        return CompletableFuture.allOf(
            *listenAddrs.map { network.listen(it) }.toTypedArray()
        )
    }

    override fun stop(): CompletableFuture<Void> {
        return CompletableFuture.allOf(
            network.close()
        )
    }

    override fun addStreamVisitor(streamVisitor: ChannelVisitor<Stream>) {
        streamVisitors += streamVisitor
    }

    override fun removeStreamVisitor(streamVisitor: ChannelVisitor<Stream>) {
        streamVisitors -= streamVisitor
    }

    override fun addProtocolHandler(protocolBinding: ProtocolBinding<Any>) {
        protocolHandlers += protocolBinding
    }

    override fun removeProtocolHandler(protocolBinding: ProtocolBinding<Any>) {
        protocolHandlers -= protocolBinding
    }

    override fun getProtocols(): List<ProtocolBinding<Any>> {
        return protocolHandlers
    }

    override fun addConnectionHandler(handler: ConnectionHandler) {
        connectionHandlers += handler
    }

    override fun removeConnectionHandler(handler: ConnectionHandler) {
        connectionHandlers -= handler
    }

    /**
     * This overload owns BOTH halves of the operation: it picks the connection (from the network's pool,
     * or by dialling) and then creates the stream on it. A caller therefore never chose the connection, and
     * must not be handed a failure that is only about it.
     *
     * `NetworkImpl.connect` may hand back a pooled connection that is alive when it is selected and dead by
     * the time the muxer is asked for a stream — `AbstractMuxHandler.checkClosed()` then refuses with
     * [ConnectionClosedException]. No gate can prevent that: the connection can die in the gap between
     * selection and use, so narrowing the check only shrinks a window that still exists. What closes the
     * hole is honouring the contract — evict the connection this method chose and dial once more.
     *
     * The re-dial is bounded to a single attempt and the stale connection is closed FIRST, so the retry
     * cannot be served the same pool entry; a retry that could re-read it would not be a fix. Observed in
     * UrlResolver buildtest run `dfe616d9`, three firings in one run, each with an alive pooled connection
     * at selection time (`toPeer=1 toPeerClosed=0`), no new dial, and a reachable peer reported unreachable
     * ~100 ms later.
     */
    override fun <TController> newStream(protocols: List<String>, peer: PeerId, vararg addr: Multiaddr): StreamPromise<TController> {
        val streamFuture = CompletableFuture<Stream>()
        val controllerFuture = CompletableFuture<TController>()
        attemptNewStream(protocols, peer, addr, null, streamFuture, controllerFuture)
        return StreamPromise(streamFuture, controllerFuture)
    }

    private fun <TController> attemptNewStream(
        protocols: List<String>,
        peer: PeerId,
        addr: Array<out Multiaddr>,
        firstFailure: Throwable?,
        streamFuture: CompletableFuture<Stream>,
        controllerFuture: CompletableFuture<TController>
    ) {
        // Report the ORIGINAL cause when a redial also fails: the second attempt's exception describes a
        // connection the caller never saw, and on the no-address overload it is a bare
        // NothingToCompleteException, which says nothing true about why the caller's request failed.
        fun failBoth(cause: Throwable) {
            val reported = firstFailure ?: cause
            if (firstFailure != null && firstFailure !== cause) firstFailure.addSuppressed(cause)
            streamFuture.completeExceptionally(reported)
            controllerFuture.completeExceptionally(reported)
        }

        network.connect(peer, *addr).whenComplete { connection, connectFailure ->
            if (connectFailure != null) {
                failBoth(connectFailure)
                return@whenComplete
            }
            val attempt = try {
                newStream<TController>(protocols, connection)
            } catch (cause: Throwable) {
                failBoth(cause)
                return@whenComplete
            }
            // A created substream is published immediately: `HostTransportsTest.unsupportedServerProtocol`
            // pins the contract that the stream completes even when the controller then fails. Publishing
            // it is also what forfeits the right to redial - see below.
            attempt.stream.whenComplete { stream, _ ->
                if (stream != null) streamFuture.complete(stream)
            }
            attempt.controller.whenComplete { controller, failure ->
                when {
                    failure == null -> controllerFuture.complete(controller)
                    isRedialableConnectionDeath(failure, connection, addr, firstFailure) &&
                        !streamFuture.isDone -> {
                        // Close the dead connection and WAIT for the close: the network drops a connection
                        // from its pool on its close future, so dialling before that resolves can be served
                        // the very same entry. This attempt's stream is deliberately never published - it
                        // belongs to the connection being discarded.
                        connection.close().whenComplete { _, _ ->
                            attemptNewStream(protocols, peer, addr, failure, streamFuture, controllerFuture)
                        }
                    }
                    else -> failBoth(failure)
                }
            }
        }
    }

    /**
     * Whether [failure] means the CONNECTION died, as opposed to just this substream.
     *
     * The caller of this predicate additionally requires that no stream has been published yet. Once a
     * substream has been handed to the caller, redialling would deliver a controller belonging to a
     * different connection than the stream the caller already holds; at that point the honest outcome is
     * the real failure, not a silent swap. In the production case this fix targets the stream future fails
     * rather than completing, so nothing has been published and the redial is available.
     *
     * `ConnectionClosedException` is raised at substream granularity too — `ProtocolSelect` fires it from a
     * mux child's `channelUnregistered`, which a plain remote reset of one substream is enough to trigger.
     * Redialling on that would close a healthy shared connection and kill every other stream riding it
     * (identify, ping, gossip, other `newStream` callers), turning one reset substream into a full re-dial
     * and Noise handshake for everything. So the exception type alone is not enough: the connection itself
     * has to be gone.
     *
     * Checking the connection's close future is sound here rather than racy, because a muxer only refuses
     * new streams from `channelUnregistered`, which Netty fires strictly after the close future completes.
     */
    private fun isRedialableConnectionDeath(
        failure: Throwable,
        connection: Connection,
        addr: Array<out Multiaddr>,
        firstFailure: Throwable?
    ): Boolean =
        firstFailure == null &&
            addr.isNotEmpty() &&
            failure.hasCauseOfType(ConnectionClosedException::class) &&
            connection.closeFuture().isDone

    override fun <TController> newStream(protocols: List<String>, conn: Connection): StreamPromise<TController> {
        @Suppress("UNCHECKED_CAST")
        val binding =
            protocolHandlers.find { it.protocolDescriptor.matchesAny(protocols) } as? ProtocolBinding<TController>
                ?: throw NoSuchLocalProtocolException("Protocol handler not found: $protocols")
        return conn.muxerSession().createStream(listOf(binding.toInitiator(protocols)))
    }
}
