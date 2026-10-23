package io.libp2p.security.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class NoiseBindingReadinessTest {
    @Test
    fun `sole cold host with delegating Noise binding prepares before first stream`() = bindingProbe("wrapped")

    @Test
    fun `sole cold WebSocket host prepares before first stream`() = bindingProbe("ws")

    @Test
    fun `sole cold WebSocket host with delegating Noise binding prepares before first stream`() = bindingProbe("wrapped-ws")
}

private fun bindingProbe(mode: String) {
    val output = Files.createTempFile("noise-binding-readiness-", ".log")
    val process = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").absolutePath,
        "-Xmx128m",
        "-Dio.netty.eventLoopThreads=1",
        "-cp",
        System.getProperty("java.class.path"),
        "io.libp2p.security.noise.NoiseAlgorithmReadinessProbe",
        mode
    ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
    try {
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cold binding readiness probe did not finish")
        val text = Files.readString(output)
        print(text)
        assertEquals(0, process.exitValue(), text)
    } finally {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        Files.deleteIfExists(output)
    }
}
