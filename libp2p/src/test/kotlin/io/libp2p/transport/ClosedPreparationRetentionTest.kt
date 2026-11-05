package io.libp2p.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ClosedPreparationRetentionTest {
    @Test
    fun `stopped hosts release preparation waiters before shared initialization finishes`() {
        val output = Files.createTempFile("closed-preparation-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m", "-Dio.netty.eventLoopThreads=1", "-cp",
            System.getProperty("java.class.path"),
            "io.libp2p.transport.ClosedPreparationRetentionProbe"
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Stopped-host preparation child did not finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}
