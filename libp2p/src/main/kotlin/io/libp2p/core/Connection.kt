package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.Transport
import java.util.concurrent.CompletableFuture

interface Connection : P2PChannel {
    /**
     * Explicitly requests another close attempt after a failed close has settled.
     *
     * For [io.libp2p.transport.implementation.ConnectionOverNetty], if close has not
     * started, starts it. If the latest attempt is pending, observes
     * that attempt without submitting another. After success, completes successfully
     * without another close request. Each settled failure permits a later explicit retry.
     * Its ordinary [close] calls retain their original result, including after a retry.
     * Completing or cancelling the returned future must not change the owned attempt.
     *
     * The default delegates to [close] for implementations without retained close failures.
     * Implementations that retain a failed close result must override this operation.
     */
    fun retryClose(): CompletableFuture<Unit> = close()

    /**
     * Returns the [io.libp2p.core.mux.StreamMuxer.Session] which is capable of creating
     * new [Stream]s
     */
    fun muxerSession(): StreamMuxer.Session

    /**
     * Returns the [io.libp2p.core.security.SecureChannel.Session] which contains
     * security attributes of this connection
     */
    fun secureSession(): SecureChannel.Session

    /**
     * Returns the [io.libp2p.core.transport.Transport] instance behind this [Connection]
     */
    fun transport(): Transport

    /**
     * Returns the local [Multiaddr] of this [Connection]
     */
    fun localAddress(): Multiaddr

    /**
     * Returns the remote [Multiaddr] of this [Connection]
     */
    fun remoteAddress(): Multiaddr
}
