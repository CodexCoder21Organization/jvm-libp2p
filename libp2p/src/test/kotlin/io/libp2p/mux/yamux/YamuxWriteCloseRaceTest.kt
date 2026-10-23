package io.libp2p.mux.yamux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.tools.TestChannel
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.IllegalReferenceCountException
import io.netty.util.ReferenceCountUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class YamuxWriteCloseRaceTest {

    @Test
    fun `stress admitted write racing reset is delivered or fails its future`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val iterations = (0 until 100).map { iteration ->
                executor.submit { runWriteCloseRace(iteration) }
            }

            val firstFailure = iterations.firstNotNullOfOrNull { iteration ->
                try {
                    iteration.get()
                    null
                } catch (failure: ExecutionException) {
                    failure.cause ?: failure
                }
            }
            if (firstFailure != null) throw firstFailure
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun runWriteCloseRace(iteration: Int) {
        val senderFailures = ConcurrentLinkedQueue<Throwable>()
        val receiverFailures = ConcurrentLinkedQueue<Throwable>()
        val received = ByteArrayOutputStream()
        val payload = ByteArray(301) { index -> (index % 251).toByte() }

        val senderHandler = recordingYamuxHandler(
            connectionInitiator = true,
            initialWindowSize = 300,
            inboundStreamHandler = StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            failures = senderFailures
        )
        val receiverHandler = recordingYamuxHandler(
            connectionInitiator = false,
            initialWindowSize = 300,
            inboundStreamHandler = StreamHandler<Unit> { stream ->
                stream.pushHandler(
                    object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is ByteBuf) {
                                try {
                                    val bytes = ByteArray(msg.readableBytes())
                                    msg.readBytes(bytes)
                                    received.write(bytes)
                                } finally {
                                    msg.release()
                                }
                            } else {
                                ReferenceCountUtil.release(msg)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            receiverFailures += cause
                        }
                    }
                )
                CompletableFuture.completedFuture(Unit)
            },
            failures = receiverFailures
        )

        val sender = TestChannel(
            "yamux-write-close-sender-$iteration",
            true,
            YamuxFrameCodec(1_024),
            senderHandler
        )
        val receiver = TestChannel(
            "yamux-write-close-receiver-$iteration",
            false,
            YamuxFrameCodec(1_024),
            HoldDataWindowUpdates(),
            receiverHandler
        )

        val outcome = try {
            val streamPromise = senderHandler.createStream(
                StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) }
            )
            transferAll(sender, receiver)
            transferAll(receiver, sender)
            val stream = streamPromise.stream.get(5, TimeUnit.SECONDS)

            val payloadBuffer = Unpooled.wrappedBuffer(payload)
            val writeFuture = stream.writeAndFlushWithFuture(payloadBuffer)
            transferAll(sender, receiver)

            assertThat(received.size())
                .describedAs("iteration $iteration must reach the intended partial-window state")
                .isEqualTo(300)

            // Reset while the final byte remains in Yamux's sendBuffer. The write was accepted by
            // the child channel, but its future must not report success unless all 301 bytes drain.
            stream.close().get(5, TimeUnit.SECONDS)
            sender.runPendingTasks()
            transferAll(sender, receiver)
            receiver.runPendingTasks()
            sender.runPendingTasks()

            RaceOutcome(
                delivered = received.toByteArray().contentEquals(payload),
                failed = writeFuture.isCompletedExceptionally,
                payloadRefCnt = payloadBuffer.refCnt()
            )
        } finally {
            sender.finishAndReleaseAll()
            receiver.finishAndReleaseAll()
        }

        assertThat(senderFailures + receiverFailures)
            .describedAs("iteration $iteration must not corrupt a Yamux frame reference count")
            .noneMatch { it.hasCause<IllegalReferenceCountException>() }
        assertThat(outcome.payloadRefCnt)
            .describedAs("iteration $iteration must release the reset write exactly once")
            .isZero()
        assertThat(outcome.delivered || outcome.failed)
            .withFailMessage(
                "iteration $iteration silently lost an admitted Yamux write: the peer decoded " +
                    "${received.size()} of ${payload.size} bytes, but the write future reported success"
            )
            .isTrue()
    }

    private fun recordingYamuxHandler(
        connectionInitiator: Boolean,
        initialWindowSize: Int,
        inboundStreamHandler: StreamHandler<*>,
        failures: ConcurrentLinkedQueue<Throwable>
    ): YamuxHandler =
        object : YamuxHandler(
            MultistreamProtocolV1,
            1_024,
            null,
            inboundStreamHandler,
            connectionInitiator,
            1 * 1024 * 1024,
            DEFAULT_ACK_BACKLOG_LIMIT,
            initialWindowSize
        ) {
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                failures += cause
            }
        }

    private data class RaceOutcome(
        val delivered: Boolean,
        val failed: Boolean,
        val payloadRefCnt: Int
    )

    /** Holds real outbound flow-control frames at the same pipeline boundary a blocked transport does. */
    private class HoldDataWindowUpdates : ChannelOutboundHandlerAdapter() {
        private val heldWrites = mutableListOf<Pair<Any, ChannelPromise>>()

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (
                msg is YamuxFrame &&
                msg.type == YamuxType.WINDOW_UPDATE &&
                YamuxFlag.ACK !in msg.flags
            ) {
                heldWrites += msg to promise
            } else {
                ctx.write(msg, promise)
            }
        }

        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
            heldWrites.forEach { (message, writePromise) ->
                ReferenceCountUtil.release(message)
                writePromise.tryFailure(ClosedChannelException())
            }
            heldWrites.clear()
            ctx.close(promise)
        }
    }

    private fun transferAll(from: TestChannel, to: TestChannel) {
        from.runPendingTasks()
        while (true) {
            val message = from.readOutbound<Any>() ?: break
            to.writeInbound(message)
            to.runPendingTasks()
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }
}
