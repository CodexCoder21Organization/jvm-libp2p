package io.libp2p.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class CapacityResetQueueOccupancyTest {
    @ParameterizedTest
    @CsvSource(value = ["capacity, true", "capacity, false", "rejected, true", "rejected, false", "parent, true", "parent, false", "parent-failure, true", "parent-failure, false"])
    fun completedOperationsAreCheckedWhenAnOrdinaryTaskAlreadyOccupiesTheQueue(mode: String, yamux: Boolean) {
        val output = Files.createTempFile("capacity-reset-occupied-queue", ".log")
        var process: Process? = null
        try {
            process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-Xmx128m", "-Dio.netty.eventLoopThreads=1", "-Dio.netty.eventLoop.maxPendingTasks=16",
                "-cp", System.getProperty("java.class.path"),
                "io.libp2p.transport.CompletedStreamWriteProbe", "$mode-queued", yamux.toString()
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "The occupied-queue reset probe must finish")
            assertEquals(0, process.exitValue(), Files.readString(output))
        } finally {
            process?.destroyForcibly()?.waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}
