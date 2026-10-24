package io.libp2p.security.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class NoisePreparationTrafficTest {
    @Test
    fun `cold preparation from another thread leaves plaintext traffic running`() = probe("external")

    @Test
    fun `cold preparation requested on live event loop awaits readiness asynchronously`() = probe("inline")

    @Test
    fun `close during cold preparation prevents late listener publication`() = probe("close")

    @Test
    fun `construction failure preserves its cause and closes live plaintext fixtures`() = probe("construction-failure")

    private fun probe(mode: String) {
        val output = Files.createTempFile("noise-live-traffic-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m",
            "-Dio.netty.eventLoopThreads=1",
            "-cp",
            System.getProperty("java.class.path"),
            "io.libp2p.security.noise.NoisePreparationTrafficProbe",
            mode
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(awaitNoiseProbe(process, output), "Cold traffic probe did not finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}
