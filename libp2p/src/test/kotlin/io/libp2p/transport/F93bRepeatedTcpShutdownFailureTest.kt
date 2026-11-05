package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.Host
import io.libp2p.core.dsl.host
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class F93bRepeatedTcpShutdownFailureTest {
    @Test
    fun oneListenerCloseFailureStillClosesEveryListenerAndAcceptedRoot() {
        val serverHost = host {
            transports { add(::TcpTransport) }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            network {
                listen("/ip4/127.0.0.1/tcp/0")
                listen("/ip4/127.0.0.2/tcp/0")
            }
        }
        val server = serverHost.network.transports.single()
        val clients = mutableListOf<Host>()
        val accepted = LinkedBlockingQueue<Connection>()
        val listenerChannels = mutableListOf<Channel>()
        val acceptedChannels = mutableListOf<Channel>()
        var bossGroup: io.netty.channel.EventLoopGroup? = null
        var closeAttempted = false
        val failure = IOException("The test listener close failed after releasing its socket")
        try {
            serverHost.addConnectionHandler { accepted.offer(it) }
            serverHost.start().get(10, TimeUnit.SECONDS)
            val listenAddresses = serverHost.listenAddresses()
            assertEquals(2, listenAddresses.size, "The server must expose both ephemeral listener addresses")

            repeat(8) { index ->
                val clientHost = host {
                    transports { add(::TcpTransport) }
                    secureChannels { add(::NoiseXXSecureChannel) }
                    muxers { add(StreamMuxerProtocol.getYamux()) }
                }
                clients += clientHost
                clientHost.start().get(10, TimeUnit.SECONDS)
                clientHost.network.connect(serverHost.peerId, listenAddresses[index % listenAddresses.size])
                    .get(10, TimeUnit.SECONDS)
                val connection = accepted.poll(10, TimeUnit.SECONDS)
                assertTrue(connection != null, "Client $index must produce a live accepted root")
                acceptedChannels += (connection as ConnectionOverNetty).nettyChannel
            }

            assertEquals(8, acceptedChannels.size, "All eight distinct client hosts must remain connected")
            assertTrue(acceptedChannels.all { it.isOpen }, "Every accepted root must be open before close")
            acceptedChannels.mapNotNull { it.parent() }.distinct().also { listenerChannels += it }
            assertEquals(2, listenerChannels.size, "Accepted roots must cover both listener addresses")

            val firstListener = listenerChannels.first()
            val ownedGroup = firstListener.eventLoop().parent()
            bossGroup = ownedGroup
            assertTrue(listenerChannels.all { it.eventLoop().parent() === ownedGroup }, "Both listeners must use the owned boss group")
            val workerGroup = acceptedChannels.first().eventLoop().parent()
            val bossThreads = ownedGroup.map { executor ->
                executor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
            }
            val listenerCloseFutures = listenerChannels.map { it.closeFuture() }
            val rootCloseFutures = acceptedChannels.map { it.closeFuture() }

            firstListener.pipeline().addFirst(
                "release-then-fail-close",
                object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        ctx.close(ctx.newPromise()).addListener { promise.setFailure(failure) }
                    }
                }
            )
            firstListener.eventLoop().submit { }.get(10, TimeUnit.SECONDS)

            val close = server.close().also { closeAttempted = true }
            val error = assertThrows(ExecutionException::class.java) {
                close.get(10, TimeUnit.SECONDS)
            }
            assertSame(failure, error.cause, "The transport must report the injected listener failure object")
            assertTrue(ownedGroup.isTerminated, "Failed close must still terminate the owned TCP acceptor group")
            val secondError = assertThrows(ExecutionException::class.java) {
                server.close().get(10, TimeUnit.SECONDS)
            }
            assertSame(failure, secondError.cause, "Repeated transport shutdown must retain the original failed close after listener removal")


            listenerCloseFutures.forEach { future ->
                assertTrue(future.await(10, TimeUnit.SECONDS), "Every listener close future must settle")
                assertTrue(future.isSuccess, "Each listener socket must be physically closed")
            }
            rootCloseFutures.forEach { future ->
                assertTrue(future.await(10, TimeUnit.SECONDS), "Every accepted root close future must settle")
                assertTrue(future.isSuccess, "Each accepted root must be physically closed")
            }
            assertTrue(listenerChannels.all { !it.isOpen }, "Both listener channels must be closed")
            assertTrue(acceptedChannels.all { !it.isOpen }, "All eight accepted roots must be closed")

            assertTrue(ownedGroup.terminationFuture().await(10, TimeUnit.SECONDS), "The owned boss group must terminate")
            assertTrue(ownedGroup.isTerminated, "The owned boss group must be terminated")
            assertTrue(ownedGroup.terminationFuture().isSuccess, "The boss termination future must succeed")
            bossThreads.forEach { thread ->
                thread.join(10000)
                assertFalse(thread.isAlive, "Owned boss thread ${thread.name} must terminate")
            }
            assertTrue(workerGroup.isShuttingDown.not(), "Closing one transport must preserve shared workers")
            assertFalse(workerGroup.isTerminated, "The shared worker group must remain alive")
        } finally {
            try {
                if (!closeAttempted) server.close().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    if (bossGroup?.isTerminated == false) {
                        acceptedChannels.filter { it.isOpen }.forEach { it.close().syncUninterruptibly() }
                        listenerChannels.filter { it.isOpen }.forEach { it.close().syncUninterruptibly() }
                        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
                    }
                } finally {
                    CompletableFuture.allOf(*clients.map { it.stop() }.toTypedArray()).get(10, TimeUnit.SECONDS)
                }
            }
        }
    }
}
