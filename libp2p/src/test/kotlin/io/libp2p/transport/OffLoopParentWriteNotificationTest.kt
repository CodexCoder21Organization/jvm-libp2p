package io.libp2p.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class OffLoopParentWriteNotificationTest {
    @ParameterizedTest
    @ValueSource(strings = ["success", "failure", "cancel", "close", "shutdown", "blocked-close"])
    fun separateExecutorParentCompletionSurvivesChildLoopRejection(mode: String) {
        val output = Files.createTempFile("off-loop-parent-write-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m", "-Dio.netty.eventLoop.maxPendingTasks=16",
            "-cp", System.getProperty("java.class.path"),
            "io.libp2p.transport.OffLoopParentWriteNotificationProbe", mode
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "The isolated parent-write probe must finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}
