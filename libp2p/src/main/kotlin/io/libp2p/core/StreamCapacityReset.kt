package io.libp2p.core

import java.util.concurrent.CompletableFuture

/** Optional receiver operation for a child that cannot be admitted at current capacity. */
interface StreamCapacityReset {
    /** Sends a capacity reset when negotiated; otherwise closes with an ordinary reset. */
    fun resetForCapacity(): CompletableFuture<Unit>
}
