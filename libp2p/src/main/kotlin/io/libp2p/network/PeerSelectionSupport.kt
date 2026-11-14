package io.libp2p.network

import io.libp2p.core.NoSuchRemoteProtocolException
import io.libp2p.core.PeerId
import io.libp2p.etc.types.completedExceptionally
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Capability knowledge for one continuously connected peer, owned by its network. */
internal class PeerSelectionSupport(private val peer: PeerId) {
    // Membership is guarded by NetworkImpl's peer-map monitor; capability fields by this monitor.
    val connections = mutableSetOf<CompletableFuture<Unit>>()
    private var unsupported = false
    private var supported = false
    private var probe: CompletableFuture<Unit>? = null

    @Synchronized
    fun lacksProtocol(): Boolean = unsupported

    fun request(open: () -> CompletableFuture<Boolean>): CompletableFuture<Boolean> {
        val mine = CompletableFuture<Unit>()
        val previous = synchronized(this) {
            if (unsupported) return CompletableFuture.completedFuture(false)
            if (supported) null else probe.also { if (it == null) probe = mine }
        }
        if (previous != null) return previous.thenCompose { request(open) }
        val result = CompletableFuture<Boolean>()
        val source = try { open() } catch (failure: Exception) { completedExceptionally<Boolean>(failure) }
        source.whenComplete { accepted, failure ->
            val absent = selectionProtocolUnsupported(failure)
            val learned = synchronized(this) {
                val first = absent && !unsupported
                if (absent) unsupported = true
                if (failure == null) supported = true
                if (probe === mine) probe = null
                first
            }
            if (learned) {
                LoggerFactory.getLogger("io.libp2p.network.ConnectionSelection").info(
                    "Peer {} does not support /libp2p/connection-selection/1.0.0; keeping duplicate connections open and eligible",
                    peer
                )
            }
            when {
                absent -> result.complete(false)
                failure != null -> result.completeExceptionally(failure)
                else -> result.complete(accepted)
            }
            // Publish capability before waking queued decisions; each still owns its own parent.
            mine.complete(Unit)
        }
        return result
    }
}

private fun selectionProtocolUnsupported(failure: Throwable?): Boolean {
    var cause = failure
    while (cause is CompletionException || cause is ExecutionException) cause = cause.cause
    return cause is NoSuchRemoteProtocolException
}
