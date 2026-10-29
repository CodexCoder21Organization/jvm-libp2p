package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class PlainNettyTransportFailedCloseTest {
    @ParameterizedTest
    @ValueSource(strings = ["listener", "connection", "listener-throw", "listener-deferred", "caller-cancel", "caller-complete", "success"])
    fun closeTerminatesEveryAcceptorThread(outcome: String) {
        val serverHost = host {
            transports { add(::TcpTransport) }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val clientHost = host {
            transports { add(::TcpTransport) }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
        }
        val server = serverHost.network.transports.single()
        val client = clientHost.network.transports.single()
        val accepted = CompletableFuture<Connection>()
        var listener: Channel? = null
        var connectionChannel: Channel? = null
        var failingChannel: Channel? = null
        val pendingClose = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
        val failure = IOException("The test channel refused its close operation")
        try {
            serverHost.addConnectionHandler { accepted.complete(it) }
            serverHost.start().get(10, TimeUnit.SECONDS)
            clientHost.start().get(10, TimeUnit.SECONDS)
            clientHost.network.connect(serverHost.peerId, *serverHost.listenAddresses().toTypedArray()).get(10, TimeUnit.SECONDS)
            val child = (accepted.get(10, TimeUnit.SECONDS) as ConnectionOverNetty).nettyChannel
            connectionChannel = child
            val acceptor = child.parent()
            listener = acceptor
            val group = acceptor.eventLoop().parent()
            val threads = group.map { executor -> executor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS) }
            if (outcome != "success") {
                val target = if (outcome == "connection") child else acceptor
                failingChannel = target
                target.pipeline().addFirst("refuse-close", object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        if (outcome == "listener-throw") throw failure
                        if (outcome == "listener-deferred" || outcome.startsWith("caller-")) {
                            pendingClose.complete(ctx to promise)
                        } else {
                            promise.setFailure(failure)
                        }
                    }
                })
                target.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
                val shutdown = server.close()
                if (outcome.startsWith("caller-")) {
                    if (outcome == "caller-cancel") shutdown.cancel(false) else shutdown.complete(Unit)
                    val (context, promise) = pendingClose.get(10, TimeUnit.SECONDS)
                    context.close(promise)
                    group.terminationFuture().await(10, TimeUnit.SECONDS)
                } else {
                    if (outcome == "listener-deferred") pendingClose.get(10, TimeUnit.SECONDS).second.setFailure(failure)
                    val error = assertThrows(ExecutionException::class.java) { shutdown.get(10, TimeUnit.SECONDS) }
                    assertSame(failure, error.cause, "Transport close must retain the original channel-close failure")
                }
            } else {
                server.close().get(10, TimeUnit.SECONDS)
            }
            assertTrue(group.isTerminated, "Transport close must terminate the owned acceptor group after $outcome")
            assertTrue(group.terminationFuture().isSuccess, "The acceptor termination future must succeed")
            threads.forEach { thread ->
                thread.join(10000)
                assertFalse(thread.isAlive, "Acceptor thread ${thread.name} must terminate after $outcome")
            }
            assertFalse(child.eventLoop().isShuttingDown, "Closing a transport must preserve the shared worker group")
        } finally {
            failingChannel?.let { channel ->
                channel.pipeline().context("refuse-close")?.let { channel.pipeline().remove(it.handler()) }
            }
            connectionChannel?.takeIf { it.isOpen }?.close()?.syncUninterruptibly()
            listener?.takeIf { it.isOpen }?.close()?.syncUninterruptibly()
            listener?.eventLoop()?.parent()?.shutdownGracefully(0, 5, TimeUnit.SECONDS)?.syncUninterruptibly()
            client.close().get(10, TimeUnit.SECONDS)
        }
    }
}
