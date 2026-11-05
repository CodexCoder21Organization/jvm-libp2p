package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.Host
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.libp2p.transport.ws.WsTransport
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.EventLoopGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93cPlainTransportInventoryTest {
    @ParameterizedTest
    @ValueSource(strings = ["tcp", "ws"])
    fun failedListenerCloseStillSettlesFourListenersAndThirtyTwoAcceptedRoots(transportKind: String) {
        val isWebSocket = transportKind == "ws"
        val listenerAddresses = (1..4).map { hostNumber ->
            "/ip4/127.0.0.$hostNumber/tcp/0" + if (isWebSocket) "/ws" else ""
        }

        fun newHost(listenOn: List<String> = emptyList()): Host {
            return host {
                transports {
                    if (isWebSocket) add(::WsTransport) else add(::TcpTransport)
                }
                secureChannels { add(::NoiseXXSecureChannel) }
                muxers { add(StreamMuxerProtocol.getYamux()) }
                if (listenOn.isNotEmpty()) {
                    network { listenOn.forEach { listen(it) } }
                }
            }
        }

        val serverHost = newHost(listenerAddresses)
        val server = serverHost.network.transports.single()
        val clients = mutableListOf<Host>()
        val accepted = LinkedBlockingQueue<Connection>()
        val listenerChannels = mutableListOf<Channel>()
        val acceptedChannels = mutableListOf<Channel>()
        val clientChannels = mutableListOf<Channel>()
        val listenerCloseAttempts = List(4) { AtomicInteger() }
        val rootCloseAttempts = List(32) { AtomicInteger() }
        val failure = IOException("The test listener close failed after physically closing its socket")
        var bossGroup: EventLoopGroup? = null
        var closeAttempted = false

        try {
            serverHost.addConnectionHandler { accepted.offer(it) }
            serverHost.start().get(10, TimeUnit.SECONDS)
            val listenAddresses = serverHost.listenAddresses()
            assertEquals(4, listenAddresses.size, "The server must expose all four auto-assigned listeners")

            repeat(32) { index ->
                val clientHost = newHost()
                clients += clientHost
                clientHost.start().get(10, TimeUnit.SECONDS)
                val connection = clientHost.network.connect(
                    serverHost.peerId,
                    listenAddresses[index % listenAddresses.size]
                ).get(10, TimeUnit.SECONDS)
                clientChannels += (connection as ConnectionOverNetty).nettyChannel

                val inbound = accepted.poll(10, TimeUnit.SECONDS)
                assertTrue(inbound != null, "Independent client host $index must produce an accepted root")
                acceptedChannels += (inbound as ConnectionOverNetty).nettyChannel
            }

            assertEquals(32, clients.size, "Each accepted root must come from an independent client host")
            assertEquals(32, clientChannels.size, "All 32 client roots must remain tracked by their hosts")
            assertEquals(32, acceptedChannels.size, "All 32 accepted roots must remain live before close")
            assertTrue(acceptedChannels.all { it.isOpen }, "Every accepted root must be open before close")
            assertTrue(clientChannels.all { it.isOpen }, "Every client root must be open before server close")

            val listenerIdentities = Collections.newSetFromMap(IdentityHashMap<Channel, Boolean>())
            listenerIdentities.addAll(acceptedChannels.mapNotNull { it.parent() })
            listenerChannels += listenerIdentities
            assertEquals(4, listenerChannels.size, "Accepted roots must cover all four listener sockets")
            assertTrue(listenerChannels.all { it.isOpen }, "Every listener socket must be open before close")
            assertTrue(
                listenerChannels.all { it.eventLoop().parent() === listenerChannels.first().eventLoop().parent() },
                "All four listeners must use the same transport-owned boss group"
            )

            val ownedBossGroup = listenerChannels.first().eventLoop().parent()
            bossGroup = ownedBossGroup
            val sharedWorkerGroup = acceptedChannels.first().eventLoop().parent()
            val clientWorkerGroup = clientChannels.first().eventLoop().parent()
            val bossThreads = ownedBossGroup.map { executor ->
                executor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
            }
            val listenerCloseFutures = listenerChannels.map { it.closeFuture() }
            val rootCloseFutures = acceptedChannels.map { it.closeFuture() }

            fun installCloseCounter(
                channel: Channel,
                handlerName: String,
                attempts: AtomicInteger,
                failAfterPhysicalClose: IOException? = null
            ) {
                channel.pipeline().addFirst(
                    handlerName,
                    object : ChannelOutboundHandlerAdapter() {
                        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                            attempts.incrementAndGet()
                            if (failAfterPhysicalClose == null) {
                                ctx.close(promise)
                            } else {
                                ctx.close(ctx.newPromise()).addListener { promise.setFailure(failAfterPhysicalClose) }
                            }
                        }
                    }
                )
            }

            listenerChannels.forEachIndexed { index, channel ->
                installCloseCounter(
                    channel,
                    "count-listener-close-$index",
                    listenerCloseAttempts[index],
                    if (index == 0) failure else null
                )
            }
            acceptedChannels.forEachIndexed { index, channel ->
                installCloseCounter(channel, "count-root-close-$index", rootCloseAttempts[index])
            }
            (listenerChannels + acceptedChannels).map { it.eventLoop() }.distinct()
                .forEach { eventLoop -> eventLoop.submit { }.get(10, TimeUnit.SECONDS) }

            val close = server.close().also { closeAttempted = true }
            val error = assertThrows(ExecutionException::class.java) {
                close.get(10, TimeUnit.SECONDS)
            }
            assertSame(failure, error.cause, "Transport close must report the exact injected listener failure")

            listenerCloseAttempts.forEachIndexed { index, attempts ->
                assertEquals(1, attempts.get(), "Listener $index must receive exactly one boundary close request")
            }
            rootCloseAttempts.forEachIndexed { index, attempts ->
                assertEquals(1, attempts.get(), "Accepted root $index must receive exactly one boundary close request")
            }
            listenerCloseFutures.forEach { future ->
                assertTrue(future.isDone, "Every listener close future must settle before transport close returns")
                assertTrue(future.isSuccess, "Every listener socket must be physically closed")
            }
            rootCloseFutures.forEach { future ->
                assertTrue(future.isDone, "Every accepted-root close future must settle before transport close returns")
                assertTrue(future.isSuccess, "Every accepted root must be physically closed")
            }
            assertTrue(listenerChannels.all { !it.isOpen && !it.isRegistered }, "All four listener sockets must close and unregister")
            assertTrue(acceptedChannels.all { !it.isOpen && !it.isRegistered }, "All 32 accepted roots must close and unregister")

            assertTrue(ownedBossGroup.isTerminated, "The transport-owned boss group must terminate after listener failure")
            assertTrue(ownedBossGroup.terminationFuture().isDone, "The boss-group termination future must settle")
            assertTrue(ownedBossGroup.terminationFuture().isSuccess, "The boss group must terminate successfully")
            bossThreads.forEach { thread ->
                thread.join(10000)
                assertFalse(thread.isAlive, "Owned boss thread ${thread.name} must terminate")
            }
            assertFalse(sharedWorkerGroup.isShuttingDown, "Transport close must preserve the shared worker group")
            assertFalse(sharedWorkerGroup.isTerminated, "The shared worker group must remain alive")
            assertFalse(clientWorkerGroup.isShuttingDown, "Client roots must still observe a live worker group")
            assertFalse(clientWorkerGroup.isTerminated, "Independent client transports must keep their worker group alive")
        } finally {
            try {
                if (!closeAttempted) server.close().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    if (bossGroup?.isTerminated == false) {
                        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
                    }
                } finally {
                    CompletableFuture.allOf(*clients.map { it.stop() }.toTypedArray())
                        .get(10, TimeUnit.SECONDS)
                }
            }
        }
    }
}
