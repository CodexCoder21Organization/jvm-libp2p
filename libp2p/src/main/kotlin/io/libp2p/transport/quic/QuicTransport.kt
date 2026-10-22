package io.libp2p.transport.quic

import io.libp2p.core.*
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrDns
import io.libp2p.core.multiformats.Protocol.*
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.crypto.keys.generateEcdsaKeyPair
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.STREAM
import io.libp2p.etc.types.*
import io.libp2p.etc.util.MultiaddrUtils
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.security.tls.Libp2pTrustManager
import io.libp2p.security.tls.buildCert
import io.libp2p.security.tls.getJavaKey
import io.libp2p.security.tls.verifyAndExtractIdentity
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.NettyTransport
import io.libp2p.transport.implementation.closeNettyChannelOnce
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.AdaptiveByteBufAllocator
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.codec.quic.*
import io.netty.handler.ssl.ClientAuth
    /** Tracks active listener DatagramChannels by address family. */
    internal val listenerChannelsByFamily = ConcurrentHashMap<AddressFamily, Channel>()

    /**
     * A pending hole punch: the future completed when the inbound QUIC connection arrives, plus the
     * peer id we expect that connection to present. A hole punch always targets a known peer, so this
     * is never null (see [dialAsListener]).
     */
    internal data class PendingHolePunch(
        val expectedPeerId: PeerId,
        val future: CompletableFuture<QuicChannel>
    )

    /**
     * Routes a completed inbound (server-side) handshake, applying the hole-punch identity guard
     * before the connection is exposed to application handlers. Extracted from the channelActive
     * handler in [serverTransportBuilder] so the reject-before-exposure guarantee is testable without
     * a live QUIC channel.
     *
     * If [pending] is a hole punch whose identity does not match the dial target, [closeChannel] is
     * invoked and the pending future is completed exceptionally — neither [prepareConnection] nor
     * [exposeConnection] runs, so a wrong-identity peer never reaches inbound protocol handlers.
     * Otherwise the connection is prepared and then either handed to the waiting hole-punch caller
     * ([holePunchChannel]) or exposed via [exposeConnection].
     */
    internal fun routeInboundHandshake(
        remoteIdentity: io.libp2p.security.tls.TlsPeerIdentity,
        pending: PendingHolePunch?,
        prepareConnection: () -> Unit,
        closeChannel: () -> Unit,
        holePunchChannel: () -> QuicChannel,
        exposeConnection: () -> Unit
    ) {
        if (pending != null && !holePunchIdentityMatches(pending.expectedPeerId, remoteIdentity.peerId)) {
            logger.warn(
                "Hole-punched peer presented libp2p id {} but dial target was {}; closing",
                remoteIdentity.peerId,
                pending.expectedPeerId
            )
            closeChannel()
            pending.future.completeExceptionally(
                Libp2pException(
                    "Remote peer presented libp2p pubkey for ${remoteIdentity.peerId} " +
                        "but dial target was ${pending.expectedPeerId}"
                )
            )
            return
        }

        prepareConnection()

        if (pending != null) {
            // Route this validated inbound connection to the waiting hole-punch caller.
            pending.future.complete(holePunchChannel())
        } else {
            // Normal path: deliver to the connection handler. The application handler may reject
            // the connection by throwing (e.g. PeerAlreadyConnectedException for a duplicate or
            // simultaneous connection to an already-connected peer). This runs inside the server
            // pipeline's channelActive, and the handshake-waiter handler has already removed itself
            // by this point, so an escaping exception would reach the Netty pipeline tail and log a
            // noisy "exceptionCaught reached tail of pipeline" warning while leaking the channel.
            // Close the rejected connection instead, mirroring the dial paths. Only Exception is
            // caught — fatal Errors (OutOfMemoryError, LinkageError, ...) must propagate rather than
            // be downgraded to a routine rejection. The library cannot tell a deliberate rejection
            // from an application bug (the handler contract has no typed rejection), so the log stays
            // at debug to avoid reintroducing the noise this guard removes; the application owns
            // logging of its own connection decisions.
            try {
                exposeConnection()
            } catch (e: Exception) {
                logger.debug("Inbound connection rejected by handler; closing", e)
                closeChannel()
            }
        }
    }

    /** Pending hole punches keyed by the remote address we are trying to reach. */
    private val pendingHolePunches = ConcurrentHashMap<InetSocketAddress, PendingHolePunch>()

