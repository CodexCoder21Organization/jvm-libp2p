package io.libp2p.transport

import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.transport.implementation.ConnectionOverNetty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class CompletedConnectionCloseNotificationTest {
    @Test
    fun peerClosedConnectionCompletesAfterRejectedNotificationAndLoopRecovery() {
        val output = Files.createTempFile("completed-connection-close-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m",
            "-Dio.netty.eventLoopThreads=1",
            "-Dio.netty.eventLoop.maxPendingTasks=16",
            "-cp", System.getProperty("java.class.path"),
            "io.libp2p.transport.CompletedConnectionCloseProbe"
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "The isolated close probe must finish")
            val text = Files.readString(output)
            print(text)
            assertEquals(0, process.exitValue(), text)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
}

object CompletedConnectionCloseProbe {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val server = HostBuilder().keyType(KeyType.ED25519).listen("/ip4/127.0.0.1/tcp/0").build()
        val client = HostBuilder().keyType(KeyType.ED25519).build()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Netty 4.2's NIO MPSC queue clamps a configured capacity of 16 to 2048.
        val drained = CountDownLatch(2048)
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(5, TimeUnit.SECONDS) as ConnectionOverNetty
            val loop = connection.nettyChannel.eventLoop()
            server.stop().get(5, TimeUnit.SECONDS)
            connection.closeFuture().get(5, TimeUnit.SECONDS)
            loop.submit {}.get(5, TimeUnit.SECONDS)
            check(!connection.nettyChannel.isOpen) { "The real peer close must close the socket" }
            loop.execute {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "The test must release its event loop" }
            }
            check(entered.await(5, TimeUnit.SECONDS)) { "The event loop must enter the controlled wait" }
            repeat(2048) { loop.execute { drained.countDown() } }
            var rejected = false
            try {
                loop.execute { }
            } catch (_: RejectedExecutionException) {
                rejected = true
            }
            check(rejected && !loop.isShuttingDown) { "The real live event-loop queue must be full" }
            val closing = connection.close()
            release.countDown()
            check(drained.await(5, TimeUnit.SECONDS)) { "The full queue must drain" }
            loop.submit {}.get(5, TimeUnit.SECONDS)
            check(closing.isDone) {
                "A peer-closed connection must complete close after the event loop recovers"
            }
            closing.get(1, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }
}
