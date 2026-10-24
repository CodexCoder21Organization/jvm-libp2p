package io.libp2p.security.noise

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun noiseProbePhase(phase: String) {
    println("NOISE_PROBE_PHASE=$phase")
    System.out.flush()
}

internal fun awaitNoiseProbe(process: Process, output: Path): Boolean {
    // These are hang guards, not speed assertions. Startup includes JVM/fixture/JFR
    // setup; the body has its own unchanged 30s allowance; cleanup retains the
    // existing 10s resource-close guard and exit retains the existing 5s reap guard.
    // Each phase starts only after an explicit child observation, never after an
    // estimated sleep. The bounded sequence also bounds the complete process.
    val started = System.nanoTime()
    var previous = started
    for ((phase, seconds) in listOf("READY" to 30L, "BODY_COMPLETE" to 30L, "CLEANUP_COMPLETE" to 10L)) {
        val deadline = previous + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val alive = process.isAlive
            val text = Files.readString(output)
            if (text.lineSequence().any { it == "NOISE_PROBE_PHASE=$phase" }) break
            check(alive) { "Noise probe exited before $phase:\n$text" }
            check(System.nanoTime() < deadline) { "Noise probe did not reach $phase within ${seconds}s:\n$text" }
            Thread.sleep(1)
        }
        val now = System.nanoTime()
        println("NOISE_PROBE_$phase=${TimeUnit.NANOSECONDS.toMillis(now - previous)}ms")
        previous = now
    }
    return process.waitFor(5, TimeUnit.SECONDS)
}
