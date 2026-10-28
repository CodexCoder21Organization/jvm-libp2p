package io.libp2p.protocol

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.netty.buffer.ByteBuf
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

class PingSchedulerOwnershipTest {
    @Test
    fun `later streams can ping after earlier streams close`() {
        withRealPingNodes(PingProtocol()) { client, server ->
            // Sequential close and reopen is the lifecycle contract under test.
            repeat(20) {
                val opened = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                try {
                    assertTrue(opened.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS) >= 0)
                } finally {
                    opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                }
            }
        }
    }

    @Test
    fun `closing a sibling from its reply callback leaves an existing stream usable`() {
        withRealPingNodes(PingProtocol()) { client, server ->
            val anchor = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
            try {
                val anchorController = anchor.controller.get(10, TimeUnit.SECONDS)
                repeat(20) {
                    val sibling = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                    val stream = sibling.stream.get(10, TimeUnit.SECONDS)
                    try {
                        val controller = sibling.controller.get(10, TimeUnit.SECONDS)
                        assertTrue(
                            controller.ping().thenApply { elapsed ->
                                stream.close()
                                elapsed
                            }.get(10, TimeUnit.SECONDS) >= 0
                        )
                        stream.closeFuture().get(10, TimeUnit.SECONDS)
                        assertTrue(anchorController.ping().get(10, TimeUnit.SECONDS) >= 0)
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
            val protocol = PingProtocol().also { it.timeoutScheduler = scheduler }
            withRealPingNodes(protocol) { client, server ->
                val opened = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                try {
                    assertTrue(opened.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS) >= 0)
                    assertEquals(0, scheduler.queue.size, "A completed Ping must retain no scheduled timeout")
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
            val protocol = PingProtocol().also { it.timeoutScheduler = scheduler }
            withRealPingNodes(protocol) { client, server ->
                val opened = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                assertTrue(opened.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS) >= 0)
            }
            assertFalse(scheduler.isShutdown, "PingProtocol must not shut down a caller-owned scheduler")
            assertEquals(7, scheduler.submit<Int> { 7 }.get(10, TimeUnit.SECONDS))
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `replacing a scheduler retires the owned default and keeps the replacement running`() {
        val replacement = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val protocol = PingProtocol()
        var owned: java.util.concurrent.ScheduledExecutorService? = null
        try {
            withRealPingNodes(protocol) { client, server ->
                val opened = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                val controller = opened.controller.get(10, TimeUnit.SECONDS)
                assertTrue(controller.ping().get(10, TimeUnit.SECONDS) >= 0)
                owned = protocol.timeoutScheduler
                protocol.timeoutScheduler = replacement
                assertTrue(controller.ping().get(10, TimeUnit.SECONDS) >= 0)
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
        val silentResponder = object : PingProtocol() {
            override fun onStartResponder(stream: Stream): CompletableFuture<PingController> {
                val handler = object : PingResponder() {
                    override fun onMessage(stream: Stream, msg: ByteBuf) {
                        // This real peer consumes the request without replying.
                        received.countDown()
                    }
                }
                stream.pushHandler(handler)
                return CompletableFuture.completedFuture(handler)
            }
        }
        try {
            val protocol = PingProtocol().also { it.timeoutScheduler = scheduler }
            withRealPingNodes(protocol, silentResponder) { client, server ->
                val opened = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray())
                val pending = opened.controller.get(10, TimeUnit.SECONDS).ping()
                assertTrue(received.await(10, TimeUnit.SECONDS), "The peer must receive the pending Ping")
                assertEquals(1, scheduler.queue.size, "The pending Ping must own one timeout")
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

private fun withRealPingNodes(clientProtocol: PingProtocol, serverProtocol: PingProtocol = PingProtocol(), action: (Host, Host) -> Unit) {
    val server = host {
        identity { random(KeyType.ED25519) }
        muxers { +StreamMuxerProtocol.getYamux() }
        protocols { +PingBinding(serverProtocol) }
        network { listen("/ip4/127.0.0.1/tcp/0") }
    }
    val client = host {
        identity { random(KeyType.ED25519) }
        muxers { +StreamMuxerProtocol.getYamux() }
        protocols { +PingBinding(clientProtocol) }
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
