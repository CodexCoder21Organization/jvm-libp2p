package io.libp2p.mux.yamux

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PausedYamuxBackingStorageTest {
    @Test
    fun pausedPayloadDoesNotRetainDeliveredSiblingStorage() {
        // Isolate backing-storage ownership from merging at the default 64-payload bound.
        val fixture = YamuxHandlerTest().apply { pendingReadFrameLimit = 128 }
        val backingBuffers = mutableListOf<ByteBuf>()
        var queuedStorageBytes = 0
        fixture.startMultiplexor()
        try {
            val pausedId = fixture.openStreamRemote()
            val siblingId = fixture.openStreamRemote()
            drainFrames(fixture)
            fixture.ech.pipeline().addBefore(
                fixture.ech.pipeline().context(fixture.multistreamHandler).name(),
                "wire-decoder",
                YamuxFrameCodec(256)
            )
            val paused = fixture.childHandlers[0].ctx.channel()
            paused.pipeline().addFirst(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    var backing = msg as ByteBuf
                    while (backing.unwrap() != null) backing = backing.unwrap()
                    queuedStorageBytes += backing.capacity()
                    ctx.fireChannelRead(msg)
                }
            })
            paused.config().isAutoRead = false
            repeat(100) {
                // One paused byte shares an input with 256 bytes delivered to the sibling.
                assertTrue(fixture.ech.config().isAutoRead)
                val wire = Unpooled.buffer(281, 281)
                wire.writeByte(0).writeByte(YamuxType.DATA.intValue).writeShort(0)
                    .writeInt(pausedId.toInt()).writeInt(1).writeByte(42)
                wire.writeByte(0).writeByte(YamuxType.DATA.intValue).writeShort(0)
                    .writeInt(siblingId.toInt()).writeInt(256).writeZero(256)
                backingBuffers += wire
                fixture.ech.writeInbound(wire)
                drainFrames(fixture)
            }
            assertTrue(fixture.childHandlers[0].inboundMessages.isEmpty())
            assertEquals(100, fixture.childHandlers[1].inboundMessages.size)
            val retainedBytes = backingBuffers.filter { it.refCnt() > 0 }.sumOf { it.capacity() }
            assertTrue(
                retainedBytes <= 300,
                "A paused child with a 300-byte receive window and 100 queued payload bytes retained $retainedBytes bytes of input backing storage"
            )
            paused.config().isAutoRead = true
            fixture.ech.runPendingTasks()
            assertEquals(List(100) { "2a" }, fixture.childHandlers[0].inboundMessages)
            assertEquals(100, queuedStorageBytes, "Queued payloads must own exactly their 100 readable bytes of backing storage")
            assertTrue(backingBuffers.all { it.refCnt() == 0 }, "Resume must release every input buffer")
        } finally {
            fixture.cleanUpAndCheck()
            assertTrue(backingBuffers.all { it.refCnt() == 0 }, "Closing must release every input buffer")
        }
    }
}

private fun drainFrames(fixture: YamuxHandlerTest) {
    while (true) {
        val frame = fixture.ech.readOutbound<Any>() ?: return
        ReferenceCountUtil.release(frame)
    }
}
