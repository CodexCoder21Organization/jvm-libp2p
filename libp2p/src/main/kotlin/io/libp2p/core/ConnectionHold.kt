package io.libp2p.core

import io.libp2p.core.multistream.ProtocolBinding

/**
 * One caller's provisional hold on a parent connection.
 * Releasing the hold leaves its children open; each child protects the parent until it closes.
 * Cancelling a child's controller or failed negotiation closes that child and releases this hold.
 * Ordinary reservations are independent of this hold.
 */
interface ConnectionHold : AutoCloseable {
    val connection: Connection

    fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T>

    /** Establishes ordinary lifetime retention. Returns false after release or parent exclusion. */
    fun promote(): Boolean

    /** Releases only this caller's hold. Repeated release has no additional effect. */
    override fun close()
}

/** Opens a provisional child using this host's registered protocol bindings. */
fun <T> Host.newStream(protocols: List<String>, hold: ConnectionHold): StreamPromise<T> {
    @Suppress("UNCHECKED_CAST")
    val binding = getProtocols().find { it.protocolDescriptor.matchesAny(protocols) } as? ProtocolBinding<T>
        ?: throw NoSuchLocalProtocolException("Protocol handler not found: $protocols")
    return hold.createStream(listOf(binding.toInitiator(protocols)))
}
