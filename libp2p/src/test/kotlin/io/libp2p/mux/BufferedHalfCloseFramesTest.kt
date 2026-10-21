package io.libp2p.mux

import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.libp2p.mux.mplex.MplexFlag
import io.libp2p.mux.mplex.MplexFrame
import io.libp2p.mux.mplex.MplexFrameCodec
import io.libp2p.mux.mplex.MplexHandler
import io.libp2p.mux.yamux.DEFAULT_ACK_BACKLOG_LIMIT
import io.libp2p.mux.yamux.YamuxFlag
import io.libp2p.mux.yamux.YamuxFrame
import io.libp2p.mux.yamux.YamuxFrameCodec
import io.libp2p.mux.yamux.YamuxHandler
import io.libp2p.tools.TestChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayOutputStream
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BufferedHalfCloseFramesTest {
    @ParameterizedTest
    @CsvSource(
        "true,true,drain", "true,false,drain", "false,true,drain", "false,false,drain",
        "true,true,unflushed", "true,false,unflushed", "false,true,unflushed", "false,false,unflushed",
        "true,true,localReset", "false,true,localReset",
        "true,true,remoteReset", "false,true,remoteReset",
        "true,true,parentClose", "false,true,parentClose"
    )
    fun `both half closes preserve accepted writes until drain or reset`(yamux: Boolean, remoteFirst: Boolean, finish: String) {
        val accepted = CompletableFuture<Stream>()
        val receiverBytes = ByteArrayOutputStream()
        val eof = CompletableFuture<ByteArray>()
        val receiverMux = bufferedCloseMux(
            yamux,
            false,
            StreamHandler<Stream> { stream ->
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        try {
                            val bytes = ByteArray((msg as ByteBuf).readableBytes())
                            msg.readBytes(bytes)
                            receiverBytes.write(bytes)
                        } finally {
                            ReferenceCountUtil.release(msg)
                        }
                    }

                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt === RemoteWriteClosed) eof.complete(receiverBytes.toByteArray())
                        ctx.fireUserEventTriggered(evt)
                    }
                })
                accepted.complete(stream)
                CompletableFuture.completedFuture(stream)
            }
        )
        val senderMux = bufferedCloseMux(yamux, true, StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) })
        val gate = BufferedCloseTransportGate()
        val sender = TestChannel("buffered-close-sender", true, bufferedCloseCodec(yamux), gate, senderMux)
        val receiver = TestChannel("buffered-close-receiver", false, bufferedCloseCodec(yamux), receiverMux)
        val first = ByteArray(937) { (it % 251).toByte() }
        val second = ByteArray(617) { ((it + 17) % 251).toByte() }
        val firstBuffer = Unpooled.wrappedBuffer(first)
        val secondBuffer = Unpooled.wrappedBuffer(second)
        try {
            val opened = senderMux.createStream(StreamHandler<Stream> { CompletableFuture.completedFuture(it) })
            transferBufferedCloseFrames(sender, receiver)
            transferBufferedCloseFrames(receiver, sender)
            val outgoing = opened.controller.get(5, TimeUnit.SECONDS)
            val incoming = accepted.get(5, TimeUnit.SECONDS)
            if (remoteFirst) {
                incoming.closeWrite().get(5, TimeUnit.SECONDS)
                transferBufferedCloseFrames(receiver, sender)
            }
            val firstWrite = outgoing.writeAndFlushWithFuture(firstBuffer)
            val secondWrite = if (finish == "unflushed") {
                (outgoing as StreamOverNetty).nettyChannel.write(secondBuffer).toVoidCompletableFuture()
            } else {
                outgoing.writeAndFlushWithFuture(secondBuffer)
            }
            val reenteredClose = firstWrite.thenCompose { outgoing.closeWrite() }
            assertFalse(firstWrite.isDone, "Transport gate must retain the first write")
            outgoing.closeWrite().get(5, TimeUnit.SECONDS)
            if (!remoteFirst) {
                incoming.closeWrite().get(5, TimeUnit.SECONDS)
                transferBufferedCloseFrames(receiver, sender)
            }
            assertFalse(outgoing.closeFuture().isDone, "Both half-close requests must preserve pending writes")
            outgoing.closeWrite().get(5, TimeUnit.SECONDS)
            assertEquals(0, gate.finCount, "FIN must wait for every accepted child write")

            if (finish == "drain" || finish == "unflushed") {
                gate.releaseWrites()
                repeat(20) {
                    transferBufferedCloseFrames(sender, receiver)
                    transferBufferedCloseFrames(receiver, sender)
                }
                firstWrite.get(5, TimeUnit.SECONDS)
                secondWrite.get(5, TimeUnit.SECONDS)
                reenteredClose.get(5, TimeUnit.SECONDS)
                assertTrue(eof.isDone, "The peer must observe end-of-stream after draining")
                assertArrayEquals(first + second, eof.get(5, TimeUnit.SECONDS))
                assertEquals(1, gate.finCount, "The muxer must send exactly one FIN")
                assertTrue(outgoing.closeFuture().isDone)
                assertTrue(incoming.closeFuture().isDone)
            } else {
                when (finish) {
                    "localReset" -> outgoing.close().get(5, TimeUnit.SECONDS)
                    "remoteReset" -> {
                        incoming.close().get(5, TimeUnit.SECONDS)
                        transferBufferedCloseFrames(receiver, sender)
                    }
                    "parentClose" -> sender.close().sync()
                    else -> error("Unknown finish mode: $finish")
                }
                sender.runPendingTasks()
                assertTrue(firstWrite.isCompletedExceptionally, "Closing must fail the retained write")
                assertTrue(secondWrite.isCompletedExceptionally, "Closing must fail the queued write")
                assertTrue(outgoing.closeFuture().isDone, "Closing must remain immediate during a pending half-close")
                assertEquals(0, gate.finCount, "A reset must not become a FIN")
                assertFalse(eof.isDone, "A reset must not report a completed reply")
            }
        } finally {
            try {
                sender.finishAndReleaseAll()
            } finally {
                receiver.finishAndReleaseAll()
            }
        }
        assertEquals(0, firstBuffer.refCnt())
        assertEquals(0, secondBuffer.refCnt())
    }
}

private fun bufferedCloseMux(yamux: Boolean, initiator: Boolean, handler: StreamHandler<*>): MuxHandler =
    if (yamux) {
        YamuxHandler(MultistreamProtocolV1, 256, null, handler, initiator, 4096, DEFAULT_ACK_BACKLOG_LIMIT, 300)
    } else {
        MplexHandler(MultistreamProtocolV1, 256, null, handler)
    }

private fun bufferedCloseCodec(yamux: Boolean) =
    if (yamux) YamuxFrameCodec(256) else MplexFrameCodec(256)

private fun transferBufferedCloseFrames(from: TestChannel, to: TestChannel) {
    from.runPendingTasks()
    while (true) {
        val frame = from.readOutbound<Any>() ?: break
        to.writeInbound(frame)
        to.runPendingTasks()
    }
}

private class BufferedCloseTransportGate : ChannelOutboundHandlerAdapter() {
    val held = mutableListOf<Pair<Any, ChannelPromise>>()
    lateinit var context: ChannelHandlerContext
    var holding = true
    var finCount = 0
        private set

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        context = ctx
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val hasData = when (msg) {
            is YamuxFrame -> {
                if (YamuxFlag.FIN in msg.flags) finCount++
                msg.data?.isReadable == true
            }
            is MplexFrame -> {
                if (msg.flag.type == MplexFlag.Type.CLOSE) finCount++
                msg.flag.type == MplexFlag.Type.DATA && msg.data.isReadable
            }
            else -> false
        }
        if (holding && hasData) held += msg to promise else ctx.write(msg, promise)
    }

    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        val writes = held.toList()
        held.clear()
        writes.forEach { (msg, writePromise) ->
            ReferenceCountUtil.release(msg)
            writePromise.tryFailure(ClosedChannelException())
        }
        ctx.close(promise)
    }
}

private fun BufferedCloseTransportGate.releaseWrites() {
    holding = false
    val writes = held.toList()
    held.clear()
    writes.forEach { (msg, promise) -> context.write(msg, promise) }
    context.flush()
}
