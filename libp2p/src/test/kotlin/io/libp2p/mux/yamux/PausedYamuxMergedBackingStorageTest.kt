package io.libp2p.mux.yamux

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The production frame bound (64 held payload copies) with paused one-byte payloads sliced out of
 * inputs that also carry 256-byte sibling frames. Payloads 65 to 100 are merged into the 64th copy.
 * Held and delivered storage must stay exactly the paused bytes, and every input must be released.
 */
class PausedYamuxMergedBackingStorageTest {
    @Test
    fun mergedPausedPayloadsOwnExactlyTheirBytesWhenInputsAreSharedWithASibling() {
        val fixture = YamuxHandlerTest()
        val backingBuffers = mutableListOf<ByteBuf>()
        val deliveredCapacities = mutableListOf<Int>()
        val deliveredBytes = mutableListOf<Int>()
        fixture.startMultiplexor()
        try {
            val pausedId = fixture.openStreamRemote()
            val siblingId = fixture.openStreamRemote()
            drainOutbound(fixture)
            fixture.ech.pipeline().addBefore(
                fixture.ech.pipeline().context(fixture.multistreamHandler).name(),
                "wire-decoder",
                YamuxFrameCodec(256)
            )
            val paused = fixture.childHandlers[0].ctx.channel()
            paused.pipeline().addFirst(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    val data = msg as ByteBuf
                    var backing = data
                    while (backing.unwrap() != null) backing = backing.unwrap()
                    deliveredCapacities += backing.capacity()
                    for (i in data.readerIndex() until data.writerIndex()) deliveredBytes += data.getUnsignedByte(i).toInt()
                    ctx.fireChannelRead(msg)
                }
            })
            paused.config().isAutoRead = false
            repeat(100) { index ->
                // One paused byte shares an input with 256 bytes delivered to the sibling.
                assertTrue(fixture.ech.config().isAutoRead)
                val wire = Unpooled.buffer(281, 281)
                wire.writeByte(0).writeByte(YamuxType.DATA.intValue).writeShort(0)
                    .writeInt(pausedId.toInt()).writeInt(1).writeByte(index)
                wire.writeByte(0).writeByte(YamuxType.DATA.intValue).writeShort(0)
                    .writeInt(siblingId.toInt()).writeInt(256).writeZero(256)
                backingBuffers += wire
                fixture.ech.writeInbound(wire)
                drainOutbound(fixture)
            }
            assertTrue(paused.isOpen, "100 paused bytes fit the 300-byte receive window, so the stream must stay open")
            assertTrue(fixture.childHandlers[0].inboundMessages.isEmpty())
            assertEquals(100, fixture.childHandlers[1].inboundMessages.size)
            val retainedInputBytes = backingBuffers.filter { it.refCnt() > 0 }.sumOf { it.capacity() }
            assertEquals(0, retainedInputBytes, "Held copies must not retain any shared input buffer")

            paused.config().isAutoRead = true
            fixture.ech.runPendingTasks()
            assertEquals((0 until 100).toList(), deliveredBytes, "Held bytes must be delivered in order")
            assertEquals(64, deliveredCapacities.size, "64 held copies must be delivered as 64 reads")
            assertEquals(List(63) { 1 } + 37, deliveredCapacities, "Each delivered copy must own exactly its readable bytes")
            assertEquals(100, deliveredCapacities.sum(), "Delivered copies must own exactly the 100 paused bytes")
            assertEquals(List(63) { "%02x".format(it) } + (63 until 100).joinToString("") { "%02x".format(it) }, fixture.childHandlers[0].inboundMessages)
            assertTrue(paused.isOpen, "The resumed stream must remain open")
            assertTrue(backingBuffers.all { it.refCnt() == 0 }, "Resume must release every input buffer")
        } finally {
            fixture.cleanUpAndCheck()
            assertTrue(backingBuffers.all { it.refCnt() == 0 }, "Closing must release every input buffer")
        }
    }
}

private fun drainOutbound(fixture: YamuxHandlerTest) {
    while (true) {
        val frame = fixture.ech.readOutbound<Any>() ?: return
        ReferenceCountUtil.release(frame)
    }
}
