package io.libp2p.security.noise

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun noiseProbePhase(phase: String) {
    println("NOISE_PROBE_PHASE=$phase")
    System.out.flush()
}

internal fun awaitNoiseProbe(process: Process, output: Path): Boolean {
    // Phase observations share the former 30s aggregate process guard. Progress
    // never starts another aggregate allowance; the smaller cleanup/exit caps
    // still apply within the time remaining from helper entry.
    val started = System.nanoTime()
    val aggregateDeadline = started + TimeUnit.SECONDS.toNanos(30)
    var previous = started
    val durations = mutableListOf<String>()
    fun remaining(phase: String, text: String): Long {
        val now = System.nanoTime()
        val nanos = aggregateDeadline - now
        check(nanos > 0) {
            val pending = "$phase pending=${TimeUnit.NANOSECONDS.toMillis(now - previous)}ms"
            "Noise probe exceeded the 30s aggregate deadline while awaiting $phase; " +
                "phase durations: ${(durations + pending).joinToString()}:\n$text"
        }
        return nanos
    }
    for ((phase, seconds) in listOf("READY" to 30L, "BODY_COMPLETE" to 30L, "CLEANUP_COMPLETE" to 10L)) {
        val deadline = previous + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val alive = process.isAlive
            val text = Files.readString(output)
            remaining(phase, text)
            if (text.lineSequence().any { it == "NOISE_PROBE_PHASE=$phase" }) break
            check(alive) { "Noise probe exited before $phase:\n$text" }
            check(System.nanoTime() < deadline) { "Noise probe did not reach $phase within ${seconds}s:\n$text" }
            Thread.sleep(1)
        }
        val now = System.nanoTime()
        val millis = TimeUnit.NANOSECONDS.toMillis(now - previous)
        durations += "$phase=${millis}ms"
        println("NOISE_PROBE_$phase=${millis}ms")
        previous = now
    }
    val exitBudget = minOf(TimeUnit.SECONDS.toNanos(5), remaining("EXIT", Files.readString(output)))
    val exited = process.waitFor(exitBudget, TimeUnit.NANOSECONDS)
    remaining("EXIT", Files.readString(output))
    return exited
}
