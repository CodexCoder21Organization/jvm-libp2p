package io.libp2p.mux.yamux

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.StreamCapacityException
import io.libp2p.core.StreamCapacityReset
import io.libp2p.core.StreamHandler
import io.libp2p.core.StreamPromise
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.CONNECTION
import io.libp2p.multistream.MultistreamProtocolDebugV1
import io.libp2p.tools.TestChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

private fun capacityResult(future: CompletableFuture<*>): Throwable {
    assertThat(future.isCompletedExceptionally).isTrue()
    return try {
        future.get(5, TimeUnit.SECONDS)
        error("Expected an exceptional setup result")
    } catch (cause: ExecutionException) {
        cause.cause!!
    }
}

private fun capacityBytes(id: Long, reason: ByteArray): ByteBuf = Unpooled.buffer(12 + reason.size)
    .writeByte(0).writeByte(0).writeShort(8).writeInt(id.toInt()).writeInt(reason.size).writeBytes(reason)

private fun capacityTransfer(from: TestChannel, to: TestChannel) {
    from.runPendingTasks()
    generateSequence { from.readOutbound<ByteBuf>() }.forEach {
        to.writeInbound(it)
        to.runPendingTasks()
    }
}

private class CapacityPair(
    val clientNew: Boolean = true,
    val serverNew: Boolean = true,
    val muxLimit: Int = 512,
    val applicationCapacity: Boolean = true,
    val pendingController: Boolean = false
) : AutoCloseable {
    val multistream = MultistreamProtocolDebugV1()
    val received = mutableListOf<Int>()
    var admitted = 0
    val selected = CompletableFuture<String>()
    val pending = CompletableFuture<Stream>()
    val protocol = object : ProtocolBinding<Stream> {
        override val protocolDescriptor = ProtocolDescriptor("/capacity-test/1.0.0")
        override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<Stream> {
            val stream = ch as Stream
            stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    msg as ByteBuf
                    if (stream.isInitiator) {
                        received += msg.readInt()
                        msg.release()
                    } else {
                        stream.writeAndFlush(msg)
                    }
                }
            })
            return if (stream.isInitiator && pendingController) pending else CompletableFuture.completedFuture(stream)
        }
    }
    private val incoming = StreamHandler<Stream> { stream ->
        admitted++
        if (applicationCapacity && admitted > 1) {
            val operation = stream as? StreamCapacityReset
            if (operation == null) stream.reset() else operation.resetForCapacity()
            CompletableFuture<Stream>()
        } else {
            multistream.createMultistream(listOf(protocol)).toStreamHandler().handleStream(stream)
        }
    }
    val client = TestChannel("capacity-client", true)
    val server = TestChannel("capacity-server", false)
    val clientSession: StreamMuxer.Session
    init {
        fun install(channel: TestChannel, handler: StreamHandler<*>, modern: Boolean): CompletableFuture<StreamMuxer.Session> {
            val legacy = YamuxStreamMuxer(handler, multistream, DEFAULT_MAX_BUFFERED_CONNECTION_WRITES, DEFAULT_ACK_BACKLOG_LIMIT)
            val capacity = YamuxCapacityStreamMuxer(handler, multistream, maxInboundStreams = muxLimit)
            if (channel === client) {
                legacy.muxFramesDebugHandler = ChannelVisitor { selected.complete("/yamux/1.0.0") }
                capacity.muxFramesDebugHandler = ChannelVisitor { selected.complete("/urlprotocol/yamux-capacity/1.0.0") }
            }
            val bindings: List<ProtocolBinding<StreamMuxer.Session>> = if (modern) {
                listOf(capacity, legacy)
            } else {
                listOf(legacy)
            }
            val ready = multistream.createMultistream(bindings).initChannel(channel.attr(CONNECTION).get())
            channel.pipeline().fireChannelActive()
            return ready
        }
        val clientReady = install(client, StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) }, clientNew)
        val serverReady = install(server, incoming, serverNew)
        exchange()
        clientSession = clientReady.get(5, TimeUnit.SECONDS)
        serverReady.get(5, TimeUnit.SECONDS)
        assertThat(client.pipeline().get(YamuxFrameCodec::class.java)).isNotNull()
        assertThat(selected.get(5, TimeUnit.SECONDS)).isEqualTo(if (clientNew && serverNew) "/urlprotocol/yamux-capacity/1.0.0" else "/yamux/1.0.0")
    }
    fun open(): StreamPromise<Stream> = clientSession.createStream(listOf(protocol)).also { client.runPendingTasks() }
    fun exchange() {
        repeat(4) {
            capacityTransfer(client, server)
            capacityTransfer(server, client)
        }
    }
    fun verifySibling(stream: Stream) {
        stream.writeAndFlush(Unpooled.buffer(4).writeInt(42))
        exchange()
        assertThat(received).containsExactly(42)
        assertThat(client.isOpen).isTrue()
        assertThat(server.isOpen).isTrue()
    }
    override fun close() {
        client.finishAndReleaseAll()
        server.finishAndReleaseAll()
    }
}

class YamuxCapacityResultTest {
    @Test
    fun testCapacityResultKeepsSiblingUsable() {
        CapacityPair().use { pair ->
            val sibling = pair.open()
            pair.exchange()
            val siblingStream = sibling.controller.get(5, TimeUnit.SECONDS)
            val next = pair.open()
            val nextStream = next.stream.get(5, TimeUnit.SECONDS)
            pair.exchange()
            val cause = capacityResult(next.controller)
            assertThat(cause).isInstanceOf(StreamCapacityException::class.java)
            assertThat(capacityResult(nextStream.getProtocol())).isSameAs(cause)
            assertThat(sibling.controller.get(5, TimeUnit.SECONDS)).isSameAs(siblingStream)
            pair.verifySibling(siblingStream)
        }
    }

    @Test
    fun testMixedPeersKeepOrdinaryResult() {
        listOf(true to false, false to true).forEach { (clientNew, serverNew) ->
            CapacityPair(clientNew, serverNew).use { pair ->
                val sibling = pair.open()
                pair.exchange()
                val siblingStream = sibling.controller.get(5, TimeUnit.SECONDS)
                val next = pair.open()
                pair.exchange()
                assertThat(capacityResult(next.controller)).isNotInstanceOf(StreamCapacityException::class.java)
                pair.verifySibling(siblingStream)
            }
        }
    }

    @Test
    fun testPartialCapacityFrameTargetsOneChild() {
        CapacityPair().use { pair ->
            val one = pair.open()
            val two = pair.open()
            val oneStream = one.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val bytes = capacityBytes((oneStream.nettyChannel.id() as YamuxId).id, byteArrayOf(1))
            val prefix = bytes.readRetainedSlice(12)
            pair.client.writeInbound(prefix)
            assertThat(one.controller.isDone).isFalse()
            pair.client.writeInbound(bytes)
            val cause = capacityResult(one.controller)
            assertThat(cause).isInstanceOf(StreamCapacityException::class.java)
            assertThat(capacityResult(oneStream.getProtocol())).isSameAs(cause)
            assertThat(two.controller.isDone).isFalse()
            assertThat(pair.client.isOpen).isTrue()
            assertThat(bytes.refCnt()).isZero()
        }
    }

    @Test
    fun testMuxCapacityUsesSameTypedResult() {
        CapacityPair(muxLimit = 1, applicationCapacity = false).use { pair ->
            val sibling = pair.open()
            pair.exchange()
            val next = pair.open()
            pair.exchange()
            assertThat(capacityResult(next.controller)).isInstanceOf(StreamCapacityException::class.java)
            assertThat(pair.admitted).isEqualTo(1)
            pair.verifySibling(sibling.controller.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun testUnrecognizedReasonsKeepOrdinaryResult() {
        listOf(byteArrayOf(), byteArrayOf(2), byteArrayOf(1, 1)).forEach { reason ->
            CapacityPair().use { pair ->
                val opening = pair.open()
                val stream = opening.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
                val bytes = capacityBytes((stream.nettyChannel.id() as YamuxId).id, reason)
                pair.client.writeInbound(bytes)
                assertThat(capacityResult(opening.controller)).isInstanceOf(ConnectionClosedException::class.java)
                assertThat(pair.client.isOpen).isTrue()
                assertThat(bytes.refCnt()).isZero()
            }
        }
    }

    @Test
    fun testCoalescedDuplicateAndLateResetKeepSiblingPending() {
        CapacityPair().use { pair ->
            val opening = pair.open()
            val sibling = pair.open()
            val stream = opening.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val id = (stream.nettyChannel.id() as YamuxId).id
            val first = capacityBytes(id, byteArrayOf(1))
            val duplicate = capacityBytes(id, byteArrayOf(1))
            val both = Unpooled.wrappedBuffer(first, duplicate)
            pair.client.writeInbound(both)
            val cause = capacityResult(opening.controller)
            assertThat(cause).isInstanceOf(StreamCapacityException::class.java)
            val late = capacityBytes(id, byteArrayOf(2))
            pair.client.writeInbound(late)
            assertThat(capacityResult(opening.controller)).isSameAs(cause)
            assertThat(sibling.controller.isDone).isFalse()
            assertThat(pair.client.isOpen).isTrue()
            assertThat(both.refCnt()).isZero()
            assertThat(first.refCnt()).isZero()
            assertThat(duplicate.refCnt()).isZero()
            assertThat(late.refCnt()).isZero()
        }
    }

    @Test
    fun testSetupCallbackCloseRetainsCapacityCause() {
        CapacityPair().use { pair ->
            val opening = pair.open()
            val stream = opening.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            stream.getProtocol().whenComplete { _, _ -> stream.close() }
            val bytes = capacityBytes((stream.nettyChannel.id() as YamuxId).id, byteArrayOf(1))
            pair.client.writeInbound(bytes)
            val cause = capacityResult(opening.controller)
            assertThat(cause).isInstanceOf(StreamCapacityException::class.java)
            assertThat(capacityResult(stream.getProtocol())).isSameAs(cause)
            assertThat(pair.client.isOpen).isTrue()
            assertThat(bytes.refCnt()).isZero()
        }
    }

    @Test
    fun testSelectedProtocolKeepsPendingControllerCause() {
        CapacityPair(pendingController = true).use { pair ->
            val opening = pair.open()
            pair.exchange()
            val stream = opening.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            assertThat(stream.getProtocol().get(5, TimeUnit.SECONDS)).isEqualTo("/capacity-test/1.0.0")
            assertThat(opening.controller.isDone).isFalse()
            val bytes = capacityBytes((stream.nettyChannel.id() as YamuxId).id, byteArrayOf(1))
            pair.client.writeInbound(bytes)
            val cause = capacityResult(opening.controller)
            assertThat(cause).isInstanceOf(StreamCapacityException::class.java)
            pair.pending.complete(stream)
            assertThat(capacityResult(opening.controller)).isSameAs(cause)
            assertThat(stream.getProtocol().get(5, TimeUnit.SECONDS)).isEqualTo("/capacity-test/1.0.0")
            assertThat(bytes.refCnt()).isZero()
        }
    }

    @Test
    fun testCallerCompletedStreamFutureKeepsControllerCause() {
        CapacityPair().use { pair ->
            val opening = pair.open()
            val stream = opening.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            opening.stream.obtrudeValue(null)
            val bytes = capacityBytes((stream.nettyChannel.id() as YamuxId).id, byteArrayOf(1))
            pair.client.writeInbound(bytes)
            assertThat(capacityResult(opening.controller)).isInstanceOf(StreamCapacityException::class.java)
            assertThat(pair.client.isOpen).isTrue()
            assertThat(bytes.refCnt()).isZero()
        }
    }

    @Test
    fun testParentCloseKeepsOrdinaryResult() {
        CapacityPair().use { pair ->
            val opening = pair.open()
            pair.client.close()
            assertThat(capacityResult(opening.controller)).isInstanceOf(ConnectionClosedException::class.java)
            assertThat(pair.client.isOpen).isFalse()
        }
    }

    @Test
    fun testCancelledControllerKeepsProtocolCause() {
        CapacityPair().use { pair ->
            val opening = pair.open()
            val stream = opening.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            opening.controller.cancel(false)
            val bytes = capacityBytes((stream.nettyChannel.id() as YamuxId).id, byteArrayOf(1))
            pair.client.writeInbound(bytes)
            assertThat(opening.controller.isCancelled).isTrue()
            assertThat(capacityResult(stream.getProtocol())).isInstanceOf(StreamCapacityException::class.java)
            assertThat(pair.client.isOpen).isTrue()
            assertThat(bytes.refCnt()).isZero()
        }
    }

    @Test
    fun testReleasedSlotAcceptsNextChild() {
        CapacityPair(muxLimit = 1, applicationCapacity = false).use { pair ->
            val first = pair.open()
            pair.exchange()
            first.controller.get(5, TimeUnit.SECONDS).close()
            pair.exchange()
            val next = pair.open()
            pair.exchange()
            pair.verifySibling(next.controller.get(5, TimeUnit.SECONDS))
            assertThat(pair.admitted).isEqualTo(2)
        }
    }

    @Test
    fun testZeroCapacityCreatesNoInboundChild() {
        CapacityPair(muxLimit = 0, applicationCapacity = false).use { pair ->
            val opening = pair.open()
            pair.exchange()
            assertThat(capacityResult(opening.controller)).isInstanceOf(StreamCapacityException::class.java)
            assertThat(pair.admitted).isZero()
            assertThat(pair.client.isOpen).isTrue()
            assertThat(pair.server.isOpen).isTrue()
        }
    }

    @Test
    fun testLegacyMarkerKeepsOrdinaryResult() {
        CapacityPair(false, false).use { pair ->
            val opening = pair.open()
            val stream = opening.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val bytes = capacityBytes((stream.nettyChannel.id() as YamuxId).id, byteArrayOf(1))
            pair.client.writeInbound(bytes)
            assertThat(capacityResult(opening.controller)).isInstanceOf(ConnectionClosedException::class.java)
            assertThat(pair.client.isOpen).isTrue()
            assertThat(bytes.refCnt()).isZero()
        }
    }

    @Test
    fun testRepeatedCapacityCloseSendsOneReset() {
        CapacityPair().use { pair ->
            val opening = pair.open()
            val stream = opening.stream.get(5, TimeUnit.SECONDS) as StreamOverNetty
            generateSequence { pair.client.readOutbound<ByteBuf>() }.forEach { it.release() }
            val operation = stream as StreamCapacityReset
            operation.resetForCapacity().get(5, TimeUnit.SECONDS)
            operation.resetForCapacity().get(5, TimeUnit.SECONDS)
            val frames = generateSequence { pair.client.readOutbound<ByteBuf>() }.toList()
            try {
                assertThat(frames).hasSize(1)
                assertThat(frames.single().readableBytes()).isEqualTo(13)
                assertThat(frames.single().getUnsignedShort(2)).isEqualTo(8)
                assertThat(frames.single().getInt(8)).isEqualTo(1)
                assertThat(frames.single().getByte(12)).isEqualTo(1.toByte())
            } finally {
                frames.forEach { it.release() }
            }
        }
    }

    @Test
    fun testCancelledStreamFutureKeepsPendingControllerCause() {
        CapacityPair().use { pair ->
            val opening = pair.clientSession.createStream(listOf(pair.protocol))
            assertThat(opening.stream.cancel(false)).isTrue()
            pair.client.runPendingTasks()
            val frames = generateSequence { pair.client.readOutbound<ByteBuf>() }.toList()
            val id = try {
                frames.first().getUnsignedInt(4)
            } finally {
                frames.forEach { it.release() }
            }
            val bytes = capacityBytes(id, byteArrayOf(1))
            pair.client.writeInbound(bytes)
            assertThat(opening.stream.isCancelled).isTrue()
            assertThat(capacityResult(opening.controller)).isInstanceOf(StreamCapacityException::class.java)
            assertThat(pair.client.isOpen).isTrue()
            assertThat(bytes.refCnt()).isZero()
        }
    }

    @Test
    fun testCompletedSetupKeepsItsResultAfterMarkedReset() {
        CapacityPair().use { pair ->
            val opening = pair.open()
            pair.exchange()
            val stream = opening.controller.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val bytes = capacityBytes((stream.nettyChannel.id() as YamuxId).id, byteArrayOf(1))
            pair.client.writeInbound(bytes)
            assertThat(opening.controller.get(5, TimeUnit.SECONDS)).isSameAs(stream)
            assertThat(stream.getProtocol().get(5, TimeUnit.SECONDS)).isEqualTo("/capacity-test/1.0.0")
            assertThat(pair.client.isOpen).isTrue()
            assertThat(bytes.refCnt()).isZero()
        }
    }

    @Test
    fun testBindingRequiresItsSelectedProtocol() {
        val channel = TestChannel("capacity-selection-check", true)
        try {
            val binding = YamuxCapacityStreamMuxer(
                StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
                MultistreamProtocolDebugV1()
            )
            val cause = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                binding.initChannel(channel.attr(CONNECTION).get(), "/yamux/1.0.0")
            }
            assertThat(cause).hasMessage(
                "Capacity Yamux requires selected protocol /urlprotocol/yamux-capacity/1.0.0, but received /yamux/1.0.0"
            )
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun testBindingReportsNegativeLimit() {
        val channel = TestChannel("capacity-limit-check", true)
        try {
            val binding = YamuxCapacityStreamMuxer(
                StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
                MultistreamProtocolDebugV1(),
                maxInboundStreams = -1
            )
            val cause = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                binding.initChannel(channel.attr(CONNECTION).get(), "/urlprotocol/yamux-capacity/1.0.0")
            }
            assertThat(cause).hasMessage("Maximum inbound streams must be nonnegative, but received -1")
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}
