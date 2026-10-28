package io.libp2p.security.noise

import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MultistreamConcurrentConnectionsTest {
    @Test
    fun `concurrent connections negotiate independently and closing one keeps the other usable`() {
        val server = host {
            identity { random(KeyType.ED25519) }
            muxers { +StreamMuxerProtocol.getYamux() }
            protocols { +Ping() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val clients = List(2) {
            host {
                identity { random(KeyType.ED25519) }
                muxers { +StreamMuxerProtocol.getYamux() }
                protocols { +Ping() }
                network { listen("/ip4/127.0.0.1/tcp/0") }
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val acknowledged = AtomicInteger()
        try {
            server.start().get(10, TimeUnit.SECONDS)
            clients.forEach { it.start().get(10, TimeUnit.SECONDS) }
            val calls = clients.map { client ->
                workers.submit {
                    ready.countDown()
                    start.await()
                    repeat(20) {
                        val opened = client.newStream<PingController>(
                            listOf("/ipfs/ping/1.0.0"), server.peerId,
                            *server.listenAddresses().toTypedArray()
                        )
                        try {
                            assertTrue(opened.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS) >= 0)
                            acknowledged.incrementAndGet()
                        } finally {
                            opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                        }
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both connection callers must reach the start gate")
            start.countDown()
            calls.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(40, acknowledged.get(), "Every independently negotiated stream must complete its Ping")
            assertEquals(2, server.network.connections.size, "The callers must use distinct physical connections")
            assertEquals(
                clients.map { it.peerId }.toSet(),
                server.network.connections.map { it.secureSession().remoteId }.toSet()
            )

            clients[0].stop().get(10, TimeUnit.SECONDS)
            val remaining = clients[1].newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"), server.peerId,
                *server.listenAddresses().toTypedArray()
            )
            try {
                assertTrue(remaining.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS) >= 0)
            } finally {
                remaining.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
            }
        } finally {
            start.countDown()
            workers.shutdownNow()
            try {
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Both connection callers must stop")
            } finally {
                try {
                    clients[0].stop().get(10, TimeUnit.SECONDS)
                } finally {
                    try {
                        clients[1].stop().get(10, TimeUnit.SECONDS)
                    } finally {
                        server.stop().get(10, TimeUnit.SECONDS)
                    }
                }
            }
        }
    }
}
