package io.libp2p.etc.util.netty

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * PROBE BUILD ONLY — not part of any release.
 *
 * Records how far a mux substream's teardown gets. A production trace showed a substream whose close
 * future completed while the controller a caller was waiting on was never completed, which can only happen
 * if the teardown stopped before the pipeline was unregistered. This records each hop so a firing names its
 * own abort point instead of leaving it to be inferred.
 */
object ChildChannelTeardownProbe {
    private const val MAX_EVENTS = 400
    private val events = ConcurrentLinkedQueue<String>()
    private val dropped = AtomicLong(0)
    private val startedAtNanos = System.nanoTime()

    fun record(event: String) {
        if (events.size >= MAX_EVENTS) {
            dropped.incrementAndGet()
            return
        }
        val atMs = (System.nanoTime() - startedAtNanos) / 1_000_000
        events.add("+${atMs}ms $event")
    }

    fun snapshot(): String {
        val recorded = events.toList()
        val lost = dropped.get()
        return recorded.joinToString(" | ") + if (lost > 0) " | (dropped $lost)" else ""
    }

    fun clear() {
        events.clear()
        dropped.set(0)
    }
}
