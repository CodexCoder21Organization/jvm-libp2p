package io.libp2p.security.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class NoisePreparationFailureTest {
    @Test
    fun `failing cold preparation reaches external builder caller and leaves worker responsive`() = probe("external")

    @Test
    fun `failing cold preparation reaches event loop start caller and leaves worker responsive`() = probe("inline")

    @Test
    fun `completed role digest is destroyed before the next role preparation fails`() = probe("external-second")

    private fun probe(mode: String) {
        val output = Files.createTempFile("noise-failure-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m",
            "-Dio.netty.eventLoopThreads=1",
            "-cp",
            System.getProperty("java.class.path"),
            "io.libp2p.security.noise.NoisePreparationFailureProbe",
            mode
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cold preparation failure probe did not finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}
