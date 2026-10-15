package io.libp2p.multistream

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.security.SecureChannel
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.util.CharsetUtil
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

private const val HANDOFF_PROTOCOL = "/test/coalesced-negotiation/1.0.0"

class CoalescedNegotiationTcpTest {
    @ParameterizedTest
    @ValueSource(strings = ["mplex", "yamux"])
    fun selectedStageActivatesOnceWhenPlaintextRecordsArriveTogether(muxer: String) {
        val batches = ConcurrentHashMap<P2PChannel, PlaintextRecordBatch>()
        val announcements = ConcurrentLinkedQueue<String>()
        val received = CompletableFuture<String>()
        val coalescedRead = CompletableFuture<Int>()
        val binding = object : StrictProtocolBinding<Stream>(
            HANDOFF_PROTOCOL,
            object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> =
                    CompletableFuture.completedFuture(stream)

                override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                    stream.pushHandler(object : SimpleChannelInboundHandler<ByteBuf>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            received.complete(msg.toString(CharsetUtil.UTF_8))
                        }
                    })
                    return CompletableFuture.completedFuture(stream)
                }
            }
        ) {}
        val mux = if (muxer == "mplex") StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        fun newHost(server: Boolean): Host = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels {
                add { key, _ ->
                    val plaintext = PlaintextInsecureChannel(key)
                    if (!server) {
                        plaintext
                    } else {
                        object : SecureChannel by plaintext {
                            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out SecureChannel.Session> {
                                ch.pushHandler(object : ChannelDuplexHandler() {
                                    override fun channelActive(ctx: ChannelHandlerContext) {
                                        ctx.fireChannelActive()
                                        batches.getValue(ch).releaseBatch()
                                    }
                                })
                                return plaintext.initChannel(ch, selectedProtocol)
                            }
                        }
                    }
                }
            }
            muxers { add(mux) }
            protocols { add(binding) }
            if (server) network { listen("/ip4/127.0.0.1/tcp/0") }
            debug {
                beforeSecureHandler.addHandler { channel ->
                    if (server) {
                        val batch = PlaintextRecordBatch()
                        batches[channel] = batch
                        channel.pushHandler(batch)
                    } else {
                        channel.pushHandler(CoalescePlaintextRecords(coalescedRead))
                    }
                }
                if (!server) {
                    afterSecureHandler.addHandler { channel ->
                        channel.pushHandler(object : ChannelDuplexHandler() {
                            override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                                if (msg is ByteBuf && msg.isReadable) {
                                    val length = msg.getUnsignedByte(msg.readerIndex()).toInt()
                                    if (length == msg.readableBytes() - 1) {
                                        announcements.add(msg.toString(msg.readerIndex() + 1, length, CharsetUtil.UTF_8))
                                    }
                                }
                                ctx.write(msg, promise)
                            }
                        })
                    }
                }
            }
        }
        val client = newHost(false)
        val server = newHost(true)
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val opened = runCatching {
                client.newStream<Stream>(listOf(HANDOFF_PROTOCOL), server.peerId, server.listenAddresses().single())
                    .controller.get(5, TimeUnit.SECONDS)
            }
            assertTrue(coalescedRead.get(5, TimeUnit.SECONDS) > 0, "the selection reply and Exchange must share one inbound buffer")
            val muxProtocol = if (muxer == "mplex") "/mplex/6.7.0" else "/yamux/1.0.0"
            assertAll(
                { assertEquals(1, announcements.count { it == "/multistream/1.0.0\n" }, "multistream announcements: $announcements") },
                { assertEquals(1, announcements.count { it == "$muxProtocol\n" }, "mux announcements: $announcements") },
                { assertTrue(opened.isSuccess, "stream setup failed: ${opened.exceptionOrNull()?.stackTraceToString()}") }
            )
            val channel = (opened.getOrThrow() as StreamOverNetty).nettyChannel
            channel.writeAndFlush(Unpooled.copiedBuffer("stream is usable", CharsetUtil.UTF_8)).get(5, TimeUnit.SECONDS)
            assertEquals("stream is usable", received.get(5, TimeUnit.SECONDS))
            assertEquals(1, announcements.count { it == "/multistream/1.0.0\n" })
            assertEquals(1, announcements.count { it == "$muxProtocol\n" })
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}

// TCP may segment even a single server write. Reassemble only this known handshake boundary,
// so the regression never depends on how the OS groups loopback reads.
private class CoalescePlaintextRecords(private val delivered: CompletableFuture<Int>) : ByteToMessageDecoder() {
    private var headerPassed = false
    private var recordsPassed = false

    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        if (recordsPassed) {
            out.add(input.readRetainedSlice(input.readableBytes()))
            return
        }
        val start = input.readerIndex()
        val selectionLength = input.getUnsignedByte(start).toInt() + 1
        if (!headerPassed) {
            if (input.readableBytes() < selectionLength) return
            headerPassed = true
            out.add(input.readRetainedSlice(selectionLength))
            return
        }
        if (input.readableBytes() < selectionLength + 4) return
        val recordLength = input.getInt(start + selectionLength)
        val total = selectionLength + 4 + recordLength
        if (input.readableBytes() < total) return
        recordsPassed = true
        delivered.complete(total)
        out.add(input.readRetainedSlice(total))
    }
}

private class PlaintextRecordBatch : ChannelDuplexHandler() {
    private lateinit var context: ChannelHandlerContext
    private val buffers = mutableListOf<ByteBuf>()
    private val promises = mutableListOf<ChannelPromise>()
    private var released = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        context = ctx
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (released) {
            ctx.write(msg, promise)
        } else {
            buffers += msg as ByteBuf
            promises += promise
        }
    }

    override fun flush(ctx: ChannelHandlerContext) {
        if (released) ctx.flush()
    }

    fun releaseBatch() {
        if (released) return
        released = true
        val writes = promises.toList()
        val composite = Unpooled.wrappedBuffer(*buffers.toTypedArray())
        buffers.clear()
        promises.clear()
        context.writeAndFlush(composite).addListener { future ->
            writes.forEach { if (future.isSuccess) it.trySuccess() else it.tryFailure(future.cause()) }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        buffers.forEach { it.release() }
        buffers.clear()
        promises.forEach { it.tryFailure(ConnectionClosedException()) }
        promises.clear()
        ctx.fireChannelInactive()
    }
}
