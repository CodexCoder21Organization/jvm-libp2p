package io.libp2p.transport

import io.libp2p.core.dsl.host
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.NettyTransport
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.channel.SingleThreadIoEventLoop
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.concurrent.DefaultThreadFactory
import io.netty.util.concurrent.ImmediateEventExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class F93oCloseCallbackExecutionTest {
    @Test
    fun liveLoopDeliversInlineOnLoopAndQueuesFromAnotherThread() {
        for (completeOnLoop in listOf(true, false)) {
            val owner = host { }
            val loop = SingleThreadIoEventLoop(null, DefaultThreadFactory("f93o-callback", true), NioIoHandler.newFactory())
            val held = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
            val allowLoop = CountDownLatch(1)
            val entered = CompletableFuture<Thread>()
            val callbackThread = CompletableFuture<Thread>()
            val callbacks = AtomicInteger()
            try {
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                    val channel = Bootstrap().group(loop).channel(NioSocketChannel::class.java)
                        .handler(ChannelInboundHandlerAdapter()).connect(server.localSocketAddress).sync().channel()
                    server.accept().use {
                        val connection = ConnectionOverNetty(channel, owner.network.transports.single() as NettyTransport, true)
                        val loopThread = loop.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
                        loop.submit {
                            channel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                                override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                                    check(held.complete(ctx to promise))
                                }
                            })
                        }.get(10, TimeUnit.SECONDS)
                        val close = connection.close()
                        val observed = close.thenRun {
                            callbacks.incrementAndGet()
                            callbackThread.complete(Thread.currentThread())
                        }
                        val request = held.get(10, TimeUnit.SECONDS)
                        loop.submit {
                            val physical = DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE)
                            request.first.close(physical)
                            assertTrue(physical.isSuccess)
                        }.get(10, TimeUnit.SECONDS)
                        assertFalse(observed.isDone)
                        if (completeOnLoop) {
                            loop.submit {
                                assertTrue(request.second.trySuccess())
                                assertTrue(observed.isDone, "An ordinary on-loop close callback must run inline")
                                assertSame(Thread.currentThread(), callbackThread.getNow(null))
                            }.get(10, TimeUnit.SECONDS)
                        } else {
                            loop.execute {
                                entered.complete(Thread.currentThread())
                                check(allowLoop.await(10, TimeUnit.SECONDS))
                            }
                            assertSame(loopThread, entered.get(10, TimeUnit.SECONDS))
                            assertTrue(request.second.trySuccess())
                            assertFalse(observed.isDone, "An off-loop close callback must wait for the live loop")
                            allowLoop.countDown()
                        }
                        observed.get(10, TimeUnit.SECONDS)
                        assertSame(loopThread, callbackThread.get(10, TimeUnit.SECONDS))
                        loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
                        assertEquals(1, callbacks.get(), "Loop termination must not repeat a delivered close callback")
                    }
                }
            } finally {
                allowLoop.countDown()
                loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
                owner.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
