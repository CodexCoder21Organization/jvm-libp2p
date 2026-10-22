package io.libp2p.security.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NoiseChildStartupTest {
    @Test
    fun `qualification children start without discovering logging plugins`() {
        val javaExecutable = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            javaExecutable,
            "-Xlog:class+load=info",
            "-cp",
            System.getProperty("java.class.path"),
            NoiseHandshakePathHarness::class.java.name,
            "inline"
        ).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The startup-check child must finish within 20 seconds")
            val text = output.get(5, TimeUnit.SECONDS)
            assertEquals(0, process.exitValue(), text.lines().filter { !it.contains("[class,load]") }.joinToString("\n"))
            assertTrue(text.contains("Noise handshake inline path:"), "The child must finish the real inline Noise proof")
            assertFalse(
                text.contains("org.apache.logging.log4j.core.config.plugins.util.PluginManager source:"),
                "Noise qualification children must not discover logging plugins before exercising their handshake contract"
            )
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "The startup-check child must close after its deadline")
            }
        }
    }
}
