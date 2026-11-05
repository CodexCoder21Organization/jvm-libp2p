package io.libp2p.transport

import io.libp2p.core.Libp2pException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrComponent
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Diagnostic public re-entry trigger; this does not observe the rejected local child's close future. */
class F93fQuicLateCreationProbeTest {
    @Test
    fun addressReentryStartsShutdownBeforeTheQueuedDialCreatesItsChild() {
        val key = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(key, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val release = CountDownLatch(1)
        val shutdown = CompletableFuture<CompletableFuture<Unit>>()
        val triggered = AtomicBoolean()
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { }, null).get(10, TimeUnit.SECONDS)
            val address = server.listenAddresses().single().withP2P(PeerId.fromPubKey(key.publicKey()))
            val first = client.dial(address, { }, null).get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            val group = first.nettyChannel.parent().eventLoop().parent()
            first.close().get(10, TimeUnit.SECONDS)
            assertTrue(first.nettyChannel.parent().closeFuture().await(10, TimeUnit.SECONDS))
            val held = group.map { loop ->
                val entered = CompletableFuture<Unit>()
                loop.execute {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS)) { "The late-creation fixture must release all loops" }
                }
                entered
            }
            held.forEach { it.get(10, TimeUnit.SECONDS) }
            val components = object : AbstractList<MultiaddrComponent>() {
                override val size: Int get() = address.components.size
                override fun get(index: Int): MultiaddrComponent {
                    if (group.any { it.inEventLoop() } && triggered.compareAndSet(false, true)) {
                        shutdown.complete(client.close())
                        assertTrue(group.isShuttingDown, "The public address callback must start group shutdown before returning to connect")
                    }
                    return address.components[index]
                }
            }
            val pending = client.dial(Multiaddr(components), { fail<Unit>("A late dial must not publish") }, null)
            assertFalse(pending.isDone, "Bind must remain queued until every continuation is installed")
            release.countDown()
            val failure = assertThrows(ExecutionException::class.java) { pending.get(10, TimeUnit.SECONDS) }
            assertTrue(triggered.get(), "The address must re-enter shutdown from the dial's loop")
            assertTrue(failure.cause is Libp2pException)
            assertEquals("Transport is closed", failure.cause!!.message)
            shutdown.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            assertTrue(group.isTerminated)
            assertEquals(0, client.activeConnections)
            assertEquals(1, server.activeListeners)
        } finally {
            release.countDown()
            try {
                client.close().get(10, TimeUnit.SECONDS)
            } finally {
                server.close().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
