package io.libp2p.etc.util.netty

import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.embedded.EmbeddedChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end stress proof for the ContainerNursery 2026-04-26 OOM mechanism.
 *
 * What the production heap dump actually showed:
 *
 *   * `nioEventLoopGroup-6-1.scheduledTaskQueue` had **13,820** entries
 *   * Every entry was a [TotalTimeoutHandler]-scheduled task
 *   * Every entry's `result == null` (NOT cancelled)
 *   * Every entry's lambda captured a `ChannelHandlerContext` whose pipeline
 *     was on a CLOSED [io.libp2p.etc.util.netty.mux.MuxChannel]
 *   * Each pinned `MuxChannel` held its full (un-destroyed) Netty pipeline,
 *     including direct buffers from the multistream-select decoder/encoder
 *     handlers — exhausting the JVM's 128 MB direct buffer pool
 *
 * The chain for the leak is therefore not "channel retained for a few ms via
 * the close() invokeLater" — it's "TotalTimeoutHandler task is still in the
 * scheduledTaskQueue, never cancelled, holding the channel + its direct
 * buffers for the *full 10 s timeout window* (or longer if the loop is
 * busy)". Cancellation only happens when `handlerRemoved` fires, which only
 * happens when `pipeline.destroy()` runs, which only happens via the
 * deferred `fireChannelUnregistered` deferred via Netty's standard close
 * flow.
 *
 * Method here: pre-queue N register + addHandler + close ops onto a real
 * single-threaded NioEventLoop. Sample the loop's `scheduledTaskQueue` size
 * during the workload, recording peak queue length. (This is the metric the
 * production heap dump showed at OOM time.)
 *
 * Expectation:
 *   * On `develop` (no fix): peak scheduledTaskQueue size grows to ~ N
 *     because every `TotalTimeoutHandler.handlerAdded` schedules a task
 *     and `cancel()` is never invoked synchronously (it waits for the
 *     deferred `pipeline.destroy()` to run via the standard close flow).
 *   * With the synchronous-destroy fix in [AbstractChildChannel.doClose]:
 *     peak scheduledTaskQueue size stays small because each close
 *     synchronously fires `handlerRemoved` → `cancel()` → task removed
 *     from `scheduledTaskQueue`.
 *
 * The bound asserted is small absolute (≤ 100). On unfixed develop the
 * peak is in the tens of thousands.
 */
class AbstractChildChannelStressTest {

    private class TestChildChannel(parent: io.netty.channel.Channel) : AbstractChildChannel(parent, null) {
        override fun localAddress0() = null
        override fun remoteAddress0() = null
        override fun doWrite(buf: ChannelOutboundBuffer) {
            while (buf.current() != null) buf.remove()
        }
        override fun metadata(): ChannelMetadata = ChannelMetadata(false)
    }

    @Suppress("DEPRECATION")
    private val loopGroup = io.netty.channel.nio.NioEventLoopGroup(1)
    private val parent = EmbeddedChannel()

    @AfterEach
    fun teardown() {
        parent.close().sync()
        loopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync()
    }

    @Test
    fun `scheduled task queue does not accumulate uncancelled timeout tasks`() {
        val total = 20_000
        val loop = loopGroup.next()
        val completed = AtomicInteger(0)

        // Read scheduledTaskQueue.size via reflection — it's a
        // package-visible field on AbstractScheduledEventExecutor.
        // (See production Phase 1 §D analyzer for the same field path.)
        val asseClass = Class.forName("io.netty.util.concurrent.AbstractScheduledEventExecutor")
        val sqField = asseClass.getDeclaredField("scheduledTaskQueue").apply { isAccessible = true }

        // Pre-queue every register+addHandler+close op. Each runs ON the
        // loop thread, mirroring the production setup where mplex frame
        // processing creates the child + close-on-error all on the same loop.
        for (i in 1..total) {
            loop.execute {
                val child = TestChildChannel(parent)
                loop.register(child).sync()
                // Each addLast schedules a TotalTimeoutHandler task with a
                // long deadline (so it doesn't fire during the test).
                child.pipeline().addLast(TotalTimeoutHandler(Duration.ofHours(1)))
                child.close()
                completed.incrementAndGet()
            }
        }

        // Sampler thread polls scheduledTaskQueue size while the loop drains.
        var peakScheduled = 0
        val maxSamples = 600
        var samples = 0
        while (samples < maxSamples) {
            Thread.sleep(10)
            val q = sqField.get(loop)
            val sz = q?.javaClass?.getMethod("size")?.invoke(q) as? Int ?: 0
            if (sz > peakScheduled) peakScheduled = sz
            samples++
            if (completed.get() == total && sz == 0) break
        }

        // Final settle: let the loop drain and confirm queue empties.
        Thread.sleep(500)
        val q = sqField.get(loop)
        val finalScheduled = q?.javaClass?.getMethod("size")?.invoke(q) as? Int ?: 0

        println(
            "stress[total=$total] peakScheduledTaskQueue=$peakScheduled " +
                "finalScheduledTaskQueue=$finalScheduled samples=$samples"
        )

        // Bound: the loop's scheduledTaskQueue must NOT accumulate the
        // uncancelled per-channel TotalTimeoutHandler tasks under churn. A
        // small floor is allowed for tasks that are momentarily in flight.
        assertThat(peakScheduled)
            .withFailMessage(
                "Peak NioEventLoop.scheduledTaskQueue size during $total " +
                    "register+close cycles was %d. This is the production " +
                    "OOM signature: every TotalTimeoutHandler that is added " +
                    "to a child-channel pipeline schedules a task whose " +
                    "lambda captures the channel's ChannelHandlerContext. " +
                    "On `develop`, those tasks are only cancelled when " +
                    "pipeline.destroy() runs — which is `invokeLater`'d via " +
                    "the standard close flow and so accumulates in the " +
                    "scheduledTaskQueue while the loop processes other work. " +
                    "AbstractChildChannel.doClose() must destroy the " +
                    "pipeline synchronously so that handlerRemoved fires " +
                    "before close() returns and cancel() removes the " +
                    "scheduled task immediately. (final = %d)",
                peakScheduled,
                finalScheduled
            )
            .isLessThan(100)
    }
}
