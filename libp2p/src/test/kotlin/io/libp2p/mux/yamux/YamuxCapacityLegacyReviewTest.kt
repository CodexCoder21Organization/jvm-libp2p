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
import io.libp2p.mux.MuxHandler
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

private fun reviewResult(future: CompletableFuture<*>): Throwable {
    assertThat(future.isCompletedExceptionally).isTrue()
    return try {
        future.get(5, TimeUnit.SECONDS)
        error("Expected an exceptional setup result")
    } catch (cause: ExecutionException) {
        cause.cause!!
    }
}

private fun reviewBytes(id: Long, reason: ByteArray): ByteBuf = Unpooled.buffer(12 + reason.size)
    .writeByte(0).writeByte(0).writeShort(8).writeInt(id.toInt()).writeInt(reason.size).writeBytes(reason)

private fun reviewTransfer(from: TestChannel, to: TestChannel) {
    from.runPendingTasks()
    generateSequence { from.readOutbound<ByteBuf>() }.forEach {
        to.writeInbound(it)
        to.runPendingTasks()
    }
}

private class LegacyReviewPair(
    val clientNew: Boolean = true,
    val serverNew: Boolean = true,
    val muxLimit: Int = 512,
    val applicationCapacity: Boolean = true,
    val pendingController: Boolean = false,
    val legacyMplex: Boolean = false
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
            val legacy: io.libp2p.core.mux.StreamMuxer = if (legacyMplex) io.libp2p.mux.mplex.MplexStreamMuxer(handler, multistream) else YamuxStreamMuxer(handler, multistream, DEFAULT_MAX_BUFFERED_CONNECTION_WRITES, DEFAULT_ACK_BACKLOG_LIMIT)
            val capacity = YamuxCapacityStreamMuxer(handler, multistream, maxInboundStreams = muxLimit)
            if (channel === client) {
                (legacy as io.libp2p.core.mux.StreamMuxerDebug).muxFramesDebugHandler = ChannelVisitor { selected.complete(if (legacyMplex) "/mplex/6.7.0" else if (legacyMplex) "/mplex/6.7.0" else "/yamux/1.0.0") }
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
        
        assertThat(selected.get(5, TimeUnit.SECONDS)).isEqualTo(if (clientNew && serverNew) "/urlprotocol/yamux-capacity/1.0.0" else if (legacyMplex) "/mplex/6.7.0" else "/yamux/1.0.0")
    }
    fun open(): StreamPromise<Stream> = clientSession.createStream(listOf(protocol)).also { client.runPendingTasks() }
    fun exchange() {
        repeat(4) {
            reviewTransfer(client, server)
            reviewTransfer(server, client)
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

class YamuxCapacityLegacyReviewTest {
    @Test
    fun testMplexMixedPeersKeepOrdinaryResult() {
        listOf(true to false, false to true).forEach { (clientNew, serverNew) ->
            LegacyReviewPair(clientNew, serverNew, legacyMplex = true).use { pair ->
                val sibling = pair.open()
                pair.exchange()
                val siblingStream = sibling.controller.get(5, TimeUnit.SECONDS)
                val next = pair.open()
                pair.exchange()
                assertThat(reviewResult(next.controller)).isInstanceOf(ConnectionClosedException::class.java)
                assertThat(pair.selected.get()).isEqualTo("/mplex/6.7.0")
                pair.verifySibling(siblingStream)
            }
        }
    }
}
