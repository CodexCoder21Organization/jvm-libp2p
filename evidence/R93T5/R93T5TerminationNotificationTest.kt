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
import io.netty.channel.IoHandler
import io.netty.channel.IoHandlerFactory
import io.netty.channel.SingleThreadIoEventLoop
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.concurrent.DefaultThreadFactory
import io.netty.util.concurrent.ImmediateEventExecutor
import io.netty.util.concurrent.ThreadAwareExecutor
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Queue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class R93T5TerminationNotificationTest {
    @Test
    fun notificationSettlesAcceptedCloseAfterSubmissionReturnsBeforeTermination() {
        val owner = host { }
        val destruction = R93T5DestructionGate()
        val loop = R93T5NotificationLoop(destruction)
        val producer = Executors.newSingleThreadExecutor()
        val held = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val channel = Bootstrap().group(loop).channel(NioSocketChannel::class.java)
                    .handler(ChannelInboundHandlerAdapter()).connect(server.localSocketAddress)
                    .sync().channel()
                server.accept().use {
                    val connection = ConnectionOverNetty(channel, owner.network.transports.single() as NettyTransport, true)
                    loop.submit {
                        channel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                            override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                                check(held.complete(ctx to promise))
                            }
                        })
                    }.get(10, TimeUnit.SECONDS)
                    val close = connection.close()
                    val request = held.get(10, TimeUnit.SECONDS)
                    val observed = close.thenRun {
                        assertFalse(Thread.holdsLock(loop.terminationFuture()), "Completion must run outside the termination promise monitor")
                        assertFalse(Thread.holdsLock(request.second), "Completion must run outside the physical promise monitor")
                    }
                    loop.submit {
                        val physical = DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE)
                        request.first.close(physical)
                        assertTrue(physical.isSuccess)
                    }.get(10, TimeUnit.SECONDS)
                    assertFalse(close.isDone)
                    val completedProducer = producer.submit {
                        loop.queueGate.producer.set(Thread.currentThread())
                        assertTrue(request.second.trySuccess())
                    }
                    loop.queueGate.entered.get(10, TimeUnit.SECONDS)
                    val terminated = loop.shutdownGracefully(0, 5, TimeUnit.SECONDS)
                    destruction.entered.get(10, TimeUnit.SECONDS)
                    assertFalse(loop.isTerminated)
                    loop.queueGate.release.countDown()
                    completedProducer.get(10, TimeUnit.SECONDS)
                    assertFalse(loop.isTerminated, "Submission must return before termination")
                    assertFalse(close.isDone, "Accepted completion must remain pending until termination notification")
                    destruction.release.countDown()
                    terminated.get(10, TimeUnit.SECONDS)
                    close.get(10, TimeUnit.SECONDS)
                    observed.get(10, TimeUnit.SECONDS)
                }
            }
        } finally {
            loop.queueGate.release.countDown()
            destruction.release.countDown()
            producer.shutdown()
            check(producer.awaitTermination(10, TimeUnit.SECONDS))
            loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            owner.stop().get(10, TimeUnit.SECONDS)
        }
    }
}

private class R93T5DestructionGate : IoHandlerFactory {
    val entered = CompletableFuture<Unit>()
    val release = CountDownLatch(1)

    override fun newHandler(executor: ThreadAwareExecutor): IoHandler {
        val actual = NioIoHandler.newFactory().newHandler(executor)
        return object : IoHandler by actual {
            override fun destroy() {
                actual.destroy()
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
    }

    override fun isChangingThreadSupported() = true
}

private class R93T5NotificationLoop(factory: IoHandlerFactory) : SingleThreadIoEventLoop(null, DefaultThreadFactory("r93t5-notification", true), factory) {
    lateinit var queueGate: R93T5NotificationQueue
        private set

    override fun newTaskQueue(maxPendingTasks: Int): Queue<Runnable> {
        val queue = R93T5NotificationQueue(super.newTaskQueue(maxPendingTasks))
        if (!::queueGate.isInitialized) queueGate = queue
        return queue
    }
}

private class R93T5NotificationQueue(private val actual: Queue<Runnable>) : Queue<Runnable> by actual {
    val producer = AtomicReference<Thread>()
    val entered = CompletableFuture<Unit>()
    val release = CountDownLatch(1)

    override fun offer(task: Runnable): Boolean {
        if (producer.compareAndSet(Thread.currentThread(), null)) {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
        }
        return actual.offer(task)
    }
}
