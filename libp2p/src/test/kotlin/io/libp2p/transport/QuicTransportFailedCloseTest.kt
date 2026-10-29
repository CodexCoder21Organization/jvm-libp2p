package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.libp2p.core.PeerId
import io.libp2p.crypto.keys.generateEd25519KeyPair
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

class QuicTransportFailedCloseTest {
    @ParameterizedTest
    @ValueSource(strings = ["listener", "listener-throw", "listener-deferred", "success"])
    fun closeTerminatesEveryWorkerThread(outcome: String) {
        val serverKey = generateEd25519KeyPair().first
        val server = QuicTransport.ECDSA(serverKey, emptyList())
        val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
        val accepted = CompletableFuture<Connection>()
        var listener: Channel? = null
        var failingChannel: Channel? = null
        val pendingClose = CompletableFuture<ChannelPromise>()
        val failure = IOException("The test channel refused its close operation")
        try {
            server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { accepted.complete(it) }, null)
                .get(10, TimeUnit.SECONDS)
            client.dial(server.listenAddresses().single().withP2P(PeerId.fromPubKey(serverKey.publicKey())), { }, null).get(10, TimeUnit.SECONDS)
            val child = (accepted.get(10, TimeUnit.SECONDS) as ConnectionOverNetty).nettyChannel
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
                        if (outcome == "listener-deferred") pendingClose.complete(promise) else promise.setFailure(failure)
                    }
                })
                target.eventLoop().submit { }.get(10, TimeUnit.SECONDS)
                val shutdown = server.close()
                if (outcome == "listener-deferred") pendingClose.get(10, TimeUnit.SECONDS).setFailure(failure)
                val error = assertThrows(ExecutionException::class.java) { shutdown.get(10, TimeUnit.SECONDS) }
                assertSame(failure, error.cause, "Transport close must retain the original channel-close failure")
            } else {
                server.close().get(10, TimeUnit.SECONDS)
            }
            assertTrue(group.isTerminated, "Transport close must terminate the owned QUIC worker group after $outcome")
            assertTrue(group.terminationFuture().isSuccess, "The QUIC worker termination future must succeed")
            threads.forEach { thread ->
                thread.join(10000)
                assertFalse(thread.isAlive, "QUIC worker thread ${thread.name} must terminate after $outcome")
            }
        } finally {
            failingChannel?.let { channel ->
                channel.pipeline().context("refuse-close")?.let { channel.pipeline().remove(it.handler()) }
            }
            listener?.takeIf { it.isOpen }?.close()?.syncUninterruptibly()
            listener?.eventLoop()?.parent()?.shutdownGracefully(0, 5, TimeUnit.SECONDS)?.syncUninterruptibly()
            client.close().get(10, TimeUnit.SECONDS)
        }
    }
}
