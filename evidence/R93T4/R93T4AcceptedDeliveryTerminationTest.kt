package io.libp2p.transport

import io.libp2p.core.dsl.host
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.NettyTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.channel.SingleThreadIoEventLoop
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.concurrent.ImmediateEventExecutor
import io.netty.util.concurrent.RejectedExecutionHandlers
import org.jctools.queues.MpscUnboundedArrayQueue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.AbstractQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class R93T4AcceptedDeliveryTerminationTest {
    @Test
    fun acceptedCloseDeliveryStillSettlesWhenSubmissionOverlapsLoopTermination() {
        val queue = R93T4GateQueue()
        val loop = R93T4GateLoop(queue)
        val channel = NioSocketChannel()
        val owner = host { }
        val connection = ConnectionOverNetty(channel, owner.network.transports.first() as NettyTransport, true)
        val held = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
        var producer: Thread? = null
        try {
            loop.register(channel).get(10, TimeUnit.SECONDS)
            loop.submit {
                channel.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        check(held.complete(ctx to promise))
                    }
                })
            }.get(10, TimeUnit.SECONDS)
            val close = connection.close()
            val request = held.get(10, TimeUnit.SECONDS)
            loop.submit {
                val physical = DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE)
                request.first.close(physical)
                assertTrue(physical.isSuccess)
            }.get(10, TimeUnit.SECONDS)
            assertTrue(channel.closeFuture().isSuccess)
            assertFalse(close.isDone)
            queue.armed.set(true)
            val finished = CompletableFuture.runAsync(
                { assertTrue(request.second.trySuccess()) },
                Executor { task ->
                    producer = Thread(task, "R93T4-close-delivery-producer").also { it.start() }
                }
            )
            assertTrue(queue.entered.await(10, TimeUnit.SECONDS))
            loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            assertTrue(loop.isTerminated)
            queue.release.countDown()
            finished.get(10, TimeUnit.SECONDS)
            producer!!.join(10000)
            assertFalse(producer!!.isAlive)
            assertTrue(close.isDone, "A completed physical close must reach its caller after accepted delivery overlaps loop termination")
            close.get(10, TimeUnit.SECONDS)
        } finally {
            queue.release.countDown()
            producer?.join(10000)
            loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            owner.stop().get(10, TimeUnit.SECONDS)
        }
    }
}

private class R93T4GateLoop(queue: R93T4GateQueue) : SingleThreadIoEventLoop(
    null,
    Executor { task -> Thread(task, "R93T4-owned-loop").start() },
    NioIoHandler.newFactory(),
    queue,
    ConcurrentLinkedQueue<Runnable>(),
    RejectedExecutionHandlers.reject()
)

private class R93T4GateQueue : AbstractQueue<Runnable>() {
    private val ordinary = MpscUnboundedArrayQueue<Runnable>(1024)
    val armed = AtomicBoolean()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    override val size: Int get() = ordinary.size
    override fun iterator(): MutableIterator<Runnable> = ordinary.iterator()
    override fun peek(): Runnable? = ordinary.peek()
    override fun poll(): Runnable? = ordinary.poll()
    override fun offer(task: Runnable): Boolean {
        if (armed.compareAndSet(true, false)) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        return ordinary.offer(task)
    }
    override fun remove(element: Runnable): Boolean = ordinary.remove(element)
}
