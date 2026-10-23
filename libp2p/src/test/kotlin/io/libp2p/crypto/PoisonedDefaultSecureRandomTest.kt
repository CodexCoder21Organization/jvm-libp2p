package io.libp2p.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MINUTES

class PoisonedDefaultSecureRandomTest {
    @Test
    fun startupAndKeyGenerationPathsAvoidDefaultSecureRandom() {
        val javaExecutable = System.getProperty("java.home") + "/bin/java"
        val process = ProcessBuilder(
            javaExecutable,
            "-cp",
            System.getProperty("java.class.path"),
            PoisonedDefaultSecureRandomChildMain::class.java.name
        ).start()
        val stdout = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        val completed = process.waitFor(2, MINUTES)
        if (!completed) {
            process.destroyForcibly().waitFor()
        }
        val exitCode = if (completed) process.exitValue() else -1

        assertEquals(
            0,
            exitCode,
            """
            Poisoned-default-provider child JVM failed.
            Completed within two minutes: $completed
            Full stderr:
            ${stderr.join()}
            Full stdout:
            ${stdout.join()}
            """.trimIndent()
        )
    }
}
