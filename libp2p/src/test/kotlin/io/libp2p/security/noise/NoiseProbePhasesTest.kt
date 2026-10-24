package io.libp2p.security.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class NoiseProbePhasesTest {
    @ParameterizedTest
    @ValueSource(strings = ["BODY_COMPLETE", "CLEANUP_COMPLETE", "EXIT"])
    fun `phase progress cannot extend the original thirty second deadline`(latePhase: String) {
        withControlledNoiseProbe(latePhase) { process, output ->
            val failure = assertThrows(IllegalStateException::class.java, {
                awaitNoiseProbe(process, output)
            }, "Noise probe accepted a child taking at least 31540ms within its individual phase bounds")
            val message = requireNotNull(failure.message)
            assertTrue(
                Regex(
                    "Noise probe exceeded the 30s aggregate deadline while awaiting $latePhase; " +
                        "phase durations: READY=\\d+ms(?:, BODY_COMPLETE=\\d+ms)?" +
                        "(?:, CLEANUP_COMPLETE=\\d+ms)?, $latePhase pending=\\d+ms:\\n" +
                        "NOISE_PROBE_PHASE=READY\\n(?:NOISE_PROBE_PHASE=BODY_COMPLETE\\n)?" +
                        "(?:NOISE_PROBE_PHASE=CLEANUP_COMPLETE\\n)?"
                ).matches(message),
                message
            )
        }
    }

    @Test
    fun `child completing all phases and exiting within aggregate deadline is accepted`() {
        withControlledNoiseProbe("success") { process, output ->
            assertTrue(awaitNoiseProbe(process, output))
            assertEquals(0, process.exitValue(), Files.readString(output))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["READY", "BODY_COMPLETE", "CLEANUP_COMPLETE"])
    fun `exiting without a required phase still fails`(missingPhase: String) {
        withControlledNoiseProbe("missing-$missingPhase") { process, output ->
            val failure = assertThrows(IllegalStateException::class.java) { awaitNoiseProbe(process, output) }
            val precedingPhases = listOf("READY", "BODY_COMPLETE", "CLEANUP_COMPLETE").takeWhile { it != missingPhase }
            assertEquals(
                "Noise probe exited before $missingPhase:\n" + precedingPhases.joinToString("") { "NOISE_PROBE_PHASE=$it\n" },
                failure.message
            )
        }
    }
}

private fun withControlledNoiseProbe(mode: String, verify: (Process, Path) -> Unit) {
    val output = Files.createTempFile("noise-probe-deadline-", ".log")
    try {
        val startup = Files.createTempFile("noise-probe-startup-", ".log")
        try {
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-Xmx32m",
                "-cp",
                System.getProperty("java.class.path"),
                "io.libp2p.security.noise.NoiseProbeDeadlineChild",
                mode,
                startup.toString()
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                // Start the controlled schedule only after the child JVM has loaded
                // its fixture. Otherwise cold startup can change which phase expires.
                val startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (Files.readString(startup) != "READY") {
                    check(process.isAlive) { "Controlled Noise probe exited during fixture startup:\n${Files.readString(output)}" }
                    check(System.nanoTime() < startupDeadline) { "Controlled Noise probe did not start its fixture within 30s" }
                    Thread.sleep(1)
                }
                process.outputStream.write(1)
                process.outputStream.flush()
                verify(process, output)
            } finally {
                process.descendants().forEach { it.destroyForcibly() }
                assertTrue(process.destroyForcibly().waitFor(5, TimeUnit.SECONDS), "Controlled Noise probe was not reaped")
            }
        } finally {
            Files.deleteIfExists(startup)
        }
    } finally {
        Files.deleteIfExists(output)
    }
}

object NoiseProbeDeadlineChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val delayed = mode in listOf("BODY_COMPLETE", "CLEANUP_COMPLETE", "EXIT")
        val phases = listOf("READY", "BODY_COMPLETE", "CLEANUP_COMPLETE")
        Files.writeString(Path.of(args[1]), "READY")
        check(System.`in`.read() == 1)
        // Deliberately exercise the real 30s process guard: every individual phase
        // fits its existing limit, but the child's total is at least 31,540ms.
        if (delayed) Thread.sleep(16000)
        for (phase in phases) {
            if (mode == "missing-$phase") return
            if (phase == "BODY_COMPLETE" && delayed) Thread.sleep(if (mode == phase) 15540 else 12000)
            if (phase == "CLEANUP_COMPLETE" && delayed && mode != "BODY_COMPLETE") {
                Thread.sleep(if (mode == phase) 3540 else 1000)
            }
            println("NOISE_PROBE_PHASE=$phase")
            System.out.flush()
        }
        if (mode == "EXIT") Thread.sleep(2540)
    }
}
