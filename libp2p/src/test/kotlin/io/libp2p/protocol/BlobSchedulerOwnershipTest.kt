package io.libp2p.protocol

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class BlobSchedulerOwnershipTest {
    @Test
    fun `later streams can blob after earlier streams close`() {
        withRealBlobNodes(BlobProtocol(32)) { client, server ->
            // Sequential close and reopen is the lifecycle contract under test.
            repeat(20) {
                val opened = client.newStream<BlobController>(listOf("/ipfs/blob-echo/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                try {
                    assertTrue(opened.controller.get(10, TimeUnit.SECONDS).blob().get(10, TimeUnit.SECONDS) >= 0)
                } finally {
                    opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                }
            }
        }
    }

    @Test
    fun `closing a sibling from its reply callback leaves an existing stream usable`() {
        withRealBlobNodes(BlobProtocol(32)) { client, server ->
            val anchor = client.newStream<BlobController>(listOf("/ipfs/blob-echo/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
            try {
                val anchorController = anchor.controller.get(10, TimeUnit.SECONDS)
                repeat(20) {
                    val sibling = client.newStream<BlobController>(listOf("/ipfs/blob-echo/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                    val stream = sibling.stream.get(10, TimeUnit.SECONDS)
                    try {
                        val controller = sibling.controller.get(10, TimeUnit.SECONDS)
                        assertTrue(
                            controller.blob().thenApply { elapsed ->
                                stream.close()
                                elapsed
                            }.get(10, TimeUnit.SECONDS) >= 0
                        )
                        stream.closeFuture().get(10, TimeUnit.SECONDS)
                        assertTrue(anchorController.blob().get(10, TimeUnit.SECONDS) >= 0)
                    } finally {
                        stream.close().get(10, TimeUnit.SECONDS)
                    }
                }
            } finally {
                anchor.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `a reply cancels its scheduled timeout before completion is published`() {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        try {
            val protocol = BlobProtocol(32).also { it.timeoutScheduler = scheduler }
            withRealBlobNodes(protocol) { client, server ->
                val opened = client.newStream<BlobController>(listOf("/ipfs/blob-echo/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                try {
                    assertTrue(opened.controller.get(10, TimeUnit.SECONDS).blob().get(10, TimeUnit.SECONDS) >= 0)
                    assertEquals(0, scheduler.queue.size, "A completed Blob must retain no scheduled timeout")
                } finally {
                    opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                }
            }
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `closing a host leaves its caller supplied scheduler usable`() {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        try {
            val protocol = BlobProtocol(32).also { it.timeoutScheduler = scheduler }
            withRealBlobNodes(protocol) { client, server ->
                val opened = client.newStream<BlobController>(listOf("/ipfs/blob-echo/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                assertTrue(opened.controller.get(10, TimeUnit.SECONDS).blob().get(10, TimeUnit.SECONDS) >= 0)
            }
            assertFalse(scheduler.isShutdown, "BlobProtocol must not shut down a caller-owned scheduler")
            assertEquals(7, scheduler.submit<Int> { 7 }.get(10, TimeUnit.SECONDS))
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `replacing a scheduler retires the owned default and keeps the replacement running`() {
        val replacement = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val protocol = BlobProtocol(32)
        var owned: java.util.concurrent.ScheduledExecutorService? = null
        try {
            withRealBlobNodes(protocol) { client, server ->
                val opened = client.newStream<BlobController>(listOf("/ipfs/blob-echo/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                val controller = opened.controller.get(10, TimeUnit.SECONDS)
                assertTrue(controller.blob().get(10, TimeUnit.SECONDS) >= 0)
                owned = protocol.timeoutScheduler
                protocol.timeoutScheduler = replacement
                assertTrue(controller.blob().get(10, TimeUnit.SECONDS) >= 0)
            }
            assertTrue(owned!!.isShutdown, "The protocol must retire its default scheduler after the last stream closes")
            assertFalse(replacement.isShutdown, "Replacing a scheduler must leave the caller's replacement running")
        } finally {
            owned?.shutdownNow()
            replacement.shutdownNow()
        }
    }

    @Test
    fun `closing a pending stream cancels its own timeout before failing the request`() {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val received = CountDownLatch(1)
        val silentResponder = object : BlobProtocol(32) {
            override fun onStartResponder(stream: Stream): CompletableFuture<BlobController> {
                val handler = object : BlobResponder() {
                    override fun onMessage(stream: Stream, msg: ByteArray) {
                        // This real peer consumes the request without replying.
                        received.countDown()
                    }
                }
                stream.pushHandler(BlobProtocol.BlobCodec())
                stream.pushHandler(handler)
                stream.pushHandler(BlobProtocol.BlobCodec())
                return CompletableFuture.completedFuture(handler)
            }
        }
        try {
            val protocol = BlobProtocol(32).also { it.timeoutScheduler = scheduler }
            withRealBlobNodes(protocol, silentResponder) { client, server ->
                val opened = client.newStream<BlobController>(listOf("/ipfs/blob-echo/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                val pending = opened.controller.get(10, TimeUnit.SECONDS).blob()
                assertTrue(received.await(10, TimeUnit.SECONDS), "The peer must receive the pending Blob")
                assertEquals(1, scheduler.queue.size, "The pending Blob must own one timeout")
                opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                val failure = assertThrows(ExecutionException::class.java) { pending.get(10, TimeUnit.SECONDS) }
                assertTrue(failure.cause is ConnectionClosedException)
                assertEquals(0, scheduler.queue.size, "A closed stream must retain no scheduled timeout")
                assertFalse(scheduler.isShutdown, "Closing a stream must leave its caller-owned scheduler running")
            }
        } finally {
            scheduler.shutdownNow()
        }
    }
}

private fun withRealBlobNodes(clientProtocol: BlobProtocol, serverProtocol: BlobProtocol = BlobProtocol(32), action: (Host, Host) -> Unit) {
    val server = host {
        identity { random(KeyType.ED25519) }
        muxers { +StreamMuxerProtocol.getYamux() }
        protocols { +BlobBinding(serverProtocol) }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    val client = host {
        identity { random(KeyType.ED25519) }
        muxers { +StreamMuxerProtocol.getYamux() }
        protocols { +BlobBinding(clientProtocol) }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    try {
        server.start().get(10, TimeUnit.SECONDS)
        client.start().get(10, TimeUnit.SECONDS)
        action(client, server)
    } finally {
        try {
            client.stop().get(10, TimeUnit.SECONDS)
        } finally {
            server.stop().get(10, TimeUnit.SECONDS)
        }
    }
}
