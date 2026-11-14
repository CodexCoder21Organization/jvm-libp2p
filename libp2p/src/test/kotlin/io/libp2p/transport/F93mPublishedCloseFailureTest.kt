package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.Host
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class F93mPublishedCloseFailureTest {
    @Test
    fun laterShutdownFailuresDoNotChangePublishedListenerErrors() {
        val serverHost = host {
            transports { add(::TcpTransport) }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            network {
                listen("/ip4/127.0.0.1/tcp/0")
                listen("/ip4/127.0.0.2/tcp/0")
            }
        }
        val transport = serverHost.network.transports.single()
        val accepted = LinkedBlockingQueue<Connection>()
        val clients = mutableListOf<Host>()
        val listeners = mutableListOf<Channel>()
        val failures = List(2) { IOException("The test listener $it refused its close operation") }
        val earlier = IOException("The first listener already reported this detail")
        failures[0].addSuppressed(earlier)
        var closeStarted = false
        var failuresPublished = 0
        try {
            serverHost.addConnectionHandler { accepted.offer(it) }
            serverHost.start().get(10, TimeUnit.SECONDS)
            val addresses = serverHost.listenAddresses()
            assertEquals(2, addresses.size)
            addresses.forEach { address ->
                val client = host {
                    transports { add(::TcpTransport) }
                    secureChannels { add(::NoiseXXSecureChannel) }
                    muxers { add(StreamMuxerProtocol.getYamux()) }
                }
                clients += client
                client.start().get(10, TimeUnit.SECONDS)
                client.network.connect(serverHost.peerId, address).get(10, TimeUnit.SECONDS)
                val inbound = requireNotNull(accepted.poll(10, TimeUnit.SECONDS))
                listeners += (inbound as ConnectionOverNetty).nettyChannel.parent()
            }
            assertEquals(2, listeners.distinct().size)
            val group = listeners.first().eventLoop().parent()
            assertTrue(listeners.all { it.eventLoop().parent() === group })
            listeners.forEachIndexed { index, listener ->
                listener.pipeline().addFirst(
                    "refuse-published-close",
                    object : ChannelOutboundHandlerAdapter() {
                        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                            promise.setFailure(failures[index])
                        }
                    }
                )
                listener.eventLoop().submit {}.get(10, TimeUnit.SECONDS)
                val published = assertThrows(ExecutionException::class.java) {
                    transport.unlisten(Multiaddr("/ip4/127.0.0.${index + 1}/tcp/0")).get(10, TimeUnit.SECONDS)
                }
                assertSame(failures[index], published.cause)
                failuresPublished++
            }
            val messages = failures.map { it.message }
            val suppressed = failures.map { it.suppressed.toList() }
            val causes = failures.map { it.cause }
            val stop = transport.close().also { closeStarted = true }
            val shutdownError = assertThrows(ExecutionException::class.java) {
                stop.get(10, TimeUnit.SECONDS)
            }
            failures.forEachIndexed { index, failure ->
                assertEquals(suppressed[index], failure.suppressed.toList(), "Published listener errors must not gain later failures")
                assertEquals(messages[index], failure.message)
                assertSame(causes[index], failure.cause)
            }
            val reached = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val pending = ArrayDeque<Throwable>()
            pending.add(requireNotNull(shutdownError.cause))
            while (pending.isNotEmpty()) {
                val failure = pending.removeFirst()
                if (reached.add(failure)) {
                    failure.cause?.let { pending.addLast(it) }
                    failure.suppressed.forEach { pending.addLast(it) }
                }
            }
            failures.forEach { assertTrue(reached.contains(it), "Shutdown must retain each original listener error") }
            assertTrue(reached.contains(earlier), "Shutdown must preserve details already present on a published error")
            val repeated = assertThrows(ExecutionException::class.java) {
                transport.close().get(10, TimeUnit.SECONDS)
            }
            assertSame(shutdownError.cause, repeated.cause)
            failures.forEachIndexed { index, failure -> assertEquals(suppressed[index], failure.suppressed.toList()) }
            assertTrue(group.isTerminated)
            listeners.forEach {
                assertTrue(it.closeFuture().isSuccess)
                assertFalse(it.isOpen)
            }
        } finally {
            try {
                if (!closeStarted) {
                    if (failuresPublished == 0) {
                        listeners.forEach { listener ->
                            if (listener.pipeline().get("refuse-published-close") != null) {
                                listener.pipeline().remove("refuse-published-close")
                            }
                        }
                        transport.close().get(10, TimeUnit.SECONDS)
                    } else {
                        assertThrows(ExecutionException::class.java) { transport.close().get(10, TimeUnit.SECONDS) }
                    }
                }
            } finally {
                CompletableFuture.allOf(*clients.map { it.stop() }.toTypedArray()).get(10, TimeUnit.SECONDS)
            }
        }
    }
}
