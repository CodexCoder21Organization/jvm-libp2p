package io.libp2p.mux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.libp2p.mux.mplex.MplexFrame
import io.libp2p.mux.mplex.MplexHandler
import io.libp2p.mux.mplex.MplexId
import io.libp2p.mux.yamux.DEFAULT_ACK_BACKLOG_LIMIT
import io.libp2p.mux.yamux.YamuxFlag
import io.libp2p.mux.yamux.YamuxFrame
import io.libp2p.mux.yamux.YamuxHandler
import io.libp2p.mux.yamux.YamuxId
import io.libp2p.mux.yamux.YamuxType
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture

class PausedChildReadCallbackCloseTest {
    @ParameterizedTest
    @ValueSource(strings = ["mplex", "yamux"])
    fun closeFromManualReadStopsDeliveryOfRemainingQueuedPayloads(muxer: String) {
        val parent = EmbeddedChannel()
        val upgrader = ConnectionUpgrader(MultistreamProtocolV1, emptyList(), MultistreamProtocolV1, emptyList())
        ConnectionOverNetty(parent, TcpTransport(upgrader), false)
        lateinit var child: Channel
        val events = mutableListOf<String>()
        var depth = 0
        var maximumDepth = 0
        val inbound = StreamHandler<Unit> { stream ->
            child = (stream as StreamOverNetty).nettyChannel
            child.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                    depth++
                    maximumDepth = maxOf(maximumDepth, depth)
                    try {
                        while (msg.isReadable) events.add("byte:${msg.readUnsignedByte()}")
                        child.close()
                        child.read()
                    } finally {
                        depth--
                    }
                }

                override fun channelReadComplete(ctx: ChannelHandlerContext) {
                    depth++
                    maximumDepth = maxOf(maximumDepth, depth)
                    try {
                        events.add("complete")
                    } finally {
                        depth--
                    }
                }

                override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                    if (evt === RemoteWriteClosed) events.add("end") else ctx.fireUserEventTriggered(evt)
                }
            })
            CompletableFuture.completedFuture(Unit)
        }
        val mplexId = MplexId(parent.id(), 2, true)
        val yamuxId = YamuxId(parent.id(), 2)
        if (muxer == "mplex") {
            parent.pipeline().addLast(MplexHandler(MultistreamProtocolV1, 256, null, inbound, 512))
        } else {
            parent.pipeline().addLast(YamuxHandler(MultistreamProtocolV1, 256, null, inbound, true, 1024 * 1024, DEFAULT_ACK_BACKLOG_LIMIT))
        }
        val inputs = mutableListOf<ByteBuf>()
        val send = { value: Int ->
            val data = Unpooled.wrappedBuffer(byteArrayOf(value.toByte()))
            inputs.add(data)
            parent.writeInbound(
                if (muxer == "mplex") {
                    MplexFrame.createDataFrame(mplexId, data)
                } else {
                    YamuxFrame(yamuxId, YamuxType.DATA, YamuxFlag.NONE, 1, data)
                }
            )
        }
        try {
            parent.writeInbound(
                if (muxer == "mplex") {
                    MplexFrame.createOpenFrame(mplexId)
                } else {
                    YamuxFrame(yamuxId, YamuxType.DATA, YamuxFlag.SYN.asSet, 0)
                }
            )
            child.config().isAutoRead = false
            repeat(32) { send(it) }
            assertTrue(events.isEmpty())
            child.read()
            parent.runPendingTasks()
            assertEquals(listOf("byte:0"), events, "Closing in a read callback must stop queued delivery")
            assertFalse(child.isOpen)
            assertTrue(parent.isOpen)
            assertTrue(parent.config().isAutoRead, "Child closure must restore the parent's previous setting")
            assertTrue(inputs.all { it.refCnt() == 0 })
        } finally {
            parent.finishAndReleaseAll()
        }
    }
}
