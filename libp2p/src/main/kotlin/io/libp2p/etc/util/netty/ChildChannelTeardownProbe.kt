package io.libp2p.etc.util.netty

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * PROBE BUILD ONLY — not part of any release.
 *
 * Records how far a mux substream's teardown gets, so a firing names its own abort point.
 *
 * Off by default and gated behind an inline check, so a build carrying it does no work — not even
 * building an event string — until a test switches it on for the window it cares about. The first
 * version of this probe recorded unconditionally on every child-channel close in the JVM, and the
 * flake it was measuring stopped reproducing; measurement must not pay for itself out of the timing
 * it is trying to observe.
 */
object ChildChannelTeardownProbe {
    private const val MAX_EVENTS = 200
    private val events = ConcurrentLinkedQueue<String>()
    private val dropped = AtomicLong(0)
    private val startedAtNanos = System.nanoTime()

    @Volatile
    @JvmField
    var enabled: Boolean = false

    inline fun record(event: () -> String) {
        if (enabled) recordEvent(event())
    }

    fun recordEvent(event: String) {
        if (events.size >= MAX_EVENTS) {
            dropped.incrementAndGet()
            return
        }
        events.add("+${(System.nanoTime() - startedAtNanos) / 1_000_000}ms $event")
    }

    /** Arms the probe and discards anything recorded earlier. */
    fun startRecording() {
        events.clear()
        dropped.set(0)
        enabled = true
    }

    fun stopRecording() {
        enabled = false
    }

    fun snapshot(): String {
        val recorded = events.toList()
        val lost = dropped.get()
        return recorded.joinToString(" | ") + if (lost > 0) " | (dropped $lost)" else ""
    }
}
