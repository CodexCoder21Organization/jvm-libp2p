package io.libp2p.etc.util.netty

import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.embedded.EmbeddedChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Reproduction of the ContainerNursery 2026-04-26 OOM heap-dump signature.
 *
 * Heap dump showed 18,506 CLOSED [io.libp2p.etc.util.netty.mux.MuxChannel]s,
 * each pinned by a queued `ScheduledFutureTask` whose lambda captured the
 * channel's `ChannelHandlerContext`. Every leaked channel still had its full
 * pipeline of multistream-select handlers attached — `pipeline.destroy()`
 * never ran, so `handlerRemoved` never fired, so `TotalTimeoutHandler.cancel()`
 * never ran, so the task stayed in `NioEventLoop.scheduledTaskQueue` forever
 * pinning the closed channel.
 *
 * Why? `AbstractChildChannel.doClose()` calls `pipeline().deregister()` —
 * which schedules the actual unregister + destroy work via `invokeLater`.
 * Under sustained high open/close rates (~78 streams/sec in production), the
 * event loop's task queue grows faster than it drains; closed-but-not-yet-
 * deregistered channels accumulate by the thousand.
 *
 * The fix is to make close synchronously destroy the pipeline rather than
 * deferring it via `invokeLater`. The two tests below exercise that:
 *
 *   1. Real [@Suppress("DEPRECATION") io.netty.channel.nio.NioEventLoopGroup] (no test shortcuts) — proves the bug under
 *      real Netty scheduling, where invokeLater actually defers work.
 *   2. [EmbeddedChannel] case — runs everything synchronously, so it always
 *      works, but is here as a sanity check.
 */
class AbstractChildChannelCloseTest {

    private class TestChildChannel(parent: io.netty.channel.Channel) : AbstractChildChannel(parent, null) {
        override fun localAddress0() = null
        override fun remoteAddress0() = null
        override fun doWrite(buf: ChannelOutboundBuffer) {
            while (buf.current() != null) buf.remove()
        }
        override fun metadata(): ChannelMetadata = ChannelMetadata(false)
    }

    @Suppress("DEPRECATION")
    private val nio = io.netty.channel.nio.NioEventLoopGroup(1)
    private val embeddedParent = EmbeddedChannel()

    @AfterEach
    fun teardown() {
        embeddedParent.close().sync()
        nio.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync()
    }

    /**
     * The 2026-06-29 ContainerNursery / kotlin.directory total outage.
     *
     * By then `snapshot-5` already cancelled the negotiation-timeout task on the
     * channel close future, so the prior signature (pending `TotalTimeoutHandler`
     * tasks in `scheduledTaskQueue`) was gone. But the heap dump still showed
     * ~30,339 *CLOSED* `MuxChannel`s retaining ~68 MB / 53% of the 128 MB heap —
     * this time pinned by deferred channel-close tasks (`AbstractChannel$AbstractUnsafe$6`,
     * the `invokeLater(fireChannelInactiveAndDeregister)`) backed up in the event
     * loop's `MpscUnboundedArrayQueue`. Each closed channel kept its FULL pipeline
     * (multistream `Negotiator` handlers + decoders + direct buffers) because
     * `pipeline.destroy()` runs only inside that deferred deregister, and under
     * sustained inbound-RPC churn the loop could not drain the deferred tasks.
     * The admission control was working (live substreams bounded; the dump's live
     * count was ~112, peak ~403, never near the cap) — the leak was retained-CLOSED
     * channels, not open ones.
     *
     * The invariant that prevents the backlog: tearing down a closed child channel's
     * pipeline (`handlerRemoved`) must happen WITHIN `close()`, before control returns
     * to the event loop to process the next frame — not on a later, possibly-starved
     * deferred task. This test closes the channel from inside a loop task and checks,
     * in that SAME task (before the loop can run any deferred task), whether the
     * pipeline was already torn down. FAILS on the deferred-destroy implementation;
     * PASSES once `doClose()` destroys the pipeline synchronously.
     */
    @Test
    fun `closing a child channel destroys its pipeline synchronously, not via a deferred event-loop task`() {
        val parent = io.netty.channel.embedded.EmbeddedChannel()
        val child = TestChildChannel(parent)
        nio.next().register(child).sync()

        val handlerRemoved = java.util.concurrent.atomic.AtomicBoolean(false)
        nio.next().submit {
            child.pipeline().addLast(
                object : io.netty.channel.ChannelHandlerAdapter() {
                    override fun handlerRemoved(ctx: io.netty.channel.ChannelHandlerContext) {
                        handlerRemoved.set(true)
                    }
                }
            )
        }.sync()

        // Close ON the event loop and read the pipeline state in the SAME task. Any
        // invokeLater'd deregister cannot have run yet, so handlerRemoved == true here
        // only if doClose() tore the pipeline down synchronously.
        val removedDuringClose = nio.next().submit<Boolean> {
            child.close()
            handlerRemoved.get()
        }.get()

        assertThat(removedDuringClose)
            .withFailMessage(
                "After close() returned on the event loop, the child channel's pipeline must " +
                    "already be destroyed (handlerRemoved fired synchronously). It was NOT: " +
                    "AbstractChildChannel.doClose() defers teardown via pipeline().deregister() " +
                    "(an invokeLater'd fireChannelUnregistered), so a closed channel keeps its full " +
                    "pipeline until the loop drains the deferred AbstractUnsafe\$6 task. Under " +
                    "sustained churn those tasks back up and the closed MuxChannels + pipelines " +
                    "exhaust the heap — the 2026-06-29 ContainerNursery / kotlin.directory OOM " +
                    "(UrlProtocol #294). doClose() must destroy the pipeline synchronously."
            )
            .isTrue

        parent.close().sync()
    }

    /**
     * The production-realistic test. Uses a real @Suppress("DEPRECATION") io.netty.channel.nio.NioEventLoopGroup (with its
     * own thread). After close().sync(), we deliberately give the loop time
     * to process queued tasks — but assert from the test thread, NOT from
     * inside the loop. If `pipeline.destroy()` is properly synchronous in
     * close, the task is cancelled by the time close().sync() returns. If
     * destroy is deferred via invokeLater, there's a window where the closed
     * channel and its scheduled task are mutually retained — that's the
     * production OOM.
     */
    @Test
    fun `closing a child channel on a real event loop cancels the timeout task synchronously`() {
        // Run the channel work on the @Suppress("DEPRECATION") io.netty.channel.nio.NioEventLoopGroup so we go through real
        // invokeLater semantics, not EmbeddedEventLoop's "everything is sync".
        val parent = io.netty.channel.embedded.EmbeddedChannel()
        // The parent here is just to give the AbstractChildChannel a parent;
        // the child's eventLoop is the NIO one we register it on.
        val child = TestChildChannel(parent)
        nio.next().register(child).sync()

        val handler = TotalTimeoutHandler(Duration.ofHours(1))
        nio.next().submit { child.pipeline().addLast(handler) }.sync()

        val taskField = TotalTimeoutHandler::class.java.getDeclaredField("timeoutTask")
        taskField.isAccessible = true

        // Take a strong reference to the task before close. Whatever happens
        // to handlerRemoved, this Java reference keeps the task observable.
        val task = nio.next().submit<ScheduledFuture<*>> { taskField.get(handler) as ScheduledFuture<*> }.get()
        assertThat(task.isCancelled).isFalse

        child.close().sync()

        // close().sync() returns when the close future completes. In current
        // (broken) Netty/AbstractChildChannel behavior, that does NOT mean
        // pipeline.destroy() has run — destroy is invokeLater'd from the
        // deregister flow which is itself invokeLater'd from close.
        //
        // Production heap dumps prove this: 18,506 channels with state=CLOSED
        // but pipelines still populated.
        assertThat(task.isCancelled)
            .withFailMessage(
                "After close().sync(), the TotalTimeoutHandler's scheduled task " +
                    "must be cancelled. Currently it is NOT — destroy() of the " +
                    "pipeline (which is what fires handlerRemoved → cancel()) is " +
                    "scheduled via invokeLater from the deregister flow. Under " +
                    "sustained load this leaves a backlog of closed channels with " +
                    "uncancelled timeout tasks pinning them in NioEventLoop." +
                    "scheduledTaskQueue. This is the production OOM signature."
            )
            .isTrue

        parent.close().sync()
    }

    /**
     * Strong-reachability check on the same code path. Same expectation: the
     * closed channel must be weakly reachable by the time close().sync()
     * returns, without any explicit task-queue pump.
     */
    @Test
    fun `closing a child channel on a real event loop makes it weakly reachable`() {
        val parent = io.netty.channel.embedded.EmbeddedChannel()
        var child: TestChildChannel? = TestChildChannel(parent)
        nio.next().register(child!!).sync()
        nio.next().submit { child!!.pipeline().addLast(TotalTimeoutHandler(Duration.ofHours(1))) }.sync()

        val ref = WeakReference(child)
        child.close().sync()
        @Suppress("UNUSED_VALUE")
        child = null

        repeat(8) {
            System.gc()
            Thread.sleep(50)
        }

        assertThat(ref.get())
            .withFailMessage(
                "After close().sync() + System.gc(), the closed child channel " +
                    "must be weakly reachable. If it is still strongly reachable, " +
                    "a queued ScheduledFutureTask in the event loop is pinning it " +
                    "via the lambda-captured ChannelHandlerContext. This is " +
                    "exactly the production OOM signature: closed MuxChannels " +
                    "retained until their deferred deregister Runnable runs."
            )
            .isNull()

        parent.close().sync()
    }
}
