package io.libp2p.transport.implementation

import io.libp2p.core.Connection
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.CONNECTION
import io.libp2p.network.ConnectionSelection
import io.netty.channel.Channel

/**
 * A Connection is a high-level wrapper around a Netty Channel representing the conduit to a peer.
 *
 * It exposes libp2p components and semantics via methods and properties.
 */
open class ConnectionOverNetty(
    ch: Channel,
    private val nettyTransport: NettyTransport,
    initiator: Boolean
) : Connection, P2PChannelOverNetty(ch, initiator) {
    @Volatile
    internal var selection: ConnectionSelection? = null
        private set

    // Set by an application close at any time, including before the selection state exists,
    // so that a close requested from a pre-upgrade handler is never lost.
    @Volatile
    private var closeWasRequested = false

    internal fun closeWasRequested(): Boolean = closeWasRequested

    internal fun enableConnectionSelection(session: StreamMuxer.Session) {
        val created = ConnectionSelection(this, session)
        selection = created
        muxerSession = created
        // Read after publishing the selection: a concurrent close either sees the selection
        // or has already set the flag, so the request is recorded in both orders.
        if (closeWasRequested) created.closeRequested()
    }

    private fun recordCloseRequest() {
        closeWasRequested = true
        selection?.closeRequested()
    }
    private val observedCloseFuture by lazy { observeConnectionClose(nettyChannel, super.closeFuture()) }

    private lateinit var muxerSession: StreamMuxer.Session
    private lateinit var secureSession: SecureChannel.Session

    init {
        ch.attr(CONNECTION).set(this)
    }

    fun setMuxerSession(ms: StreamMuxer.Session) {
        muxerSession = ms
    }
    fun setSecureSession(ss: SecureChannel.Session) {
        secureSession = ss
    }

    override fun closeFuture() = observedCloseFuture
    override fun close(): java.util.concurrent.CompletableFuture<Unit> {
        recordCloseRequest()
        return closeNettyChannelOnce(nettyChannel)
    }

    override fun retryClose(): java.util.concurrent.CompletableFuture<Unit> {
        recordCloseRequest()
        return retryNettyChannelClose(nettyChannel)
    }

    override fun muxerSession() = muxerSession
    override fun secureSession() = secureSession
    override fun transport() = nettyTransport

    override fun localAddress(): Multiaddr = nettyTransport.localAddress(nettyChannel)
    override fun remoteAddress(): Multiaddr = nettyTransport.remoteAddress(nettyChannel)
}
