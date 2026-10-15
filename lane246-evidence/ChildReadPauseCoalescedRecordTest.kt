package io.libp2p.mux

import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.libp2p.mux.mplex.MplexFlag
import io.libp2p.mux.mplex.MplexFrame
import io.libp2p.mux.yamux.YamuxFrame
import io.libp2p.mux.yamux.YamuxFlag
import io.libp2p.mux.yamux.YamuxType
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.IoEventLoop
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.util.CharsetUtil
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val COALESCED_PAUSE_PROTOCOL = "/test/child-read-pause/1.0.0"

class ChildReadPauseCoalescedRecordTest {
    @ParameterizedTest
    @ValueSource(strings = ["mplex", "yamux"])
    fun offLoopPauseDuringInFlightDeliveryQueuesLaterFramesUntilResume(muxer: String) {
        val writeBatches = java.util.concurrent.ConcurrentHashMap<io.libp2p.core.P2PChannel, ForcedPlaintextWriteBatch>()
        val traceEvents = ConcurrentLinkedQueue<String>()
        val expectedMessages = listOf("first complete message", "second complete message", "third complete message")
        val remoteEndMarker = "<remote-end>"
        val delivered = ConcurrentLinkedQueue<String>()
        val firstDeliveryEntered = CountDownLatch(1)
        val continueFirstDelivery = CountDownLatch(1)
        val firstDeliveryCompleted = CountDownLatch(1)
        val deliveredPayloads = CountDownLatch(expectedMessages.size)
        val deliveredRemoteEnd = CountDownLatch(1)
        val payloadFramesForwarded = CountDownLatch(expectedMessages.size)
        val remoteEndFrameForwarded = CountDownLatch(1)
        val responder = CompletableFuture<Stream>()
        val outboundPayloads = mutableListOf<ByteBuf>()
        val binding = object : StrictProtocolBinding<Stream>(
            COALESCED_PAUSE_PROTOCOL,
            object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> =
                    CompletableFuture.completedFuture(stream)

                override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                    val channel = (stream as StreamOverNetty).nettyChannel
                    channel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                            assertTrue(ctx.executor() is IoEventLoop, "payload delivery must run on a Netty I/O event loop")
                            assertTrue(
                                (ctx.executor() as IoEventLoop).isIoType(NioIoHandler::class.java),
                                "payload delivery must run on Netty's NIO I/O handler"
                            )
                            assertTrue(ctx.executor().inEventLoop(), "payload delivery must run on its event loop")
                            val message = msg.toString(CharsetUtil.UTF_8)
                            if (message == expectedMessages.first()) {
                                firstDeliveryEntered.countDown()
                                assertTrue(
                                    continueFirstDelivery.await(5, TimeUnit.SECONDS),
                                    "the in-flight payload delivery was not released"
                                )
                                firstDeliveryCompleted.countDown()
                            }
                            delivered.add(message)
                            deliveredPayloads.countDown()
                        }

                        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                            if (evt == RemoteWriteClosed) {
                                delivered.add(remoteEndMarker)
                                deliveredRemoteEnd.countDown()
                            } else {
                                ctx.fireUserEventTriggered(evt)
                            }
                        }
                    })

                    val parent = channel.parent()
                    val muxContext = parent.pipeline().context(MuxHandler::class.java)
                    parent.pipeline().addBefore(
                        muxContext.name(),
                        "count-paused-input-frames",
                        object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                val isPayload = when (msg) {
                                    is MplexFrame -> msg.flag.type == MplexFlag.Type.DATA && msg.data.isReadable
                                    is YamuxFrame -> msg.type == YamuxType.DATA && msg.data?.isReadable == true
                                    else -> false
                                }
                                val isRemoteEnd = when (msg) {
                                    is MplexFrame -> msg.flag.type == MplexFlag.Type.CLOSE
                                    is YamuxFrame -> msg.type == YamuxType.DATA && YamuxFlag.FIN in msg.flags
                                    else -> false
                                }
                                try {
                                    ctx.fireChannelRead(msg)
                                } finally {
                                    if (isPayload) payloadFramesForwarded.countDown()
                                    if (isRemoteEnd) remoteEndFrameForwarded.countDown()
                                }
                            }
                        }
                    )
                    responder.complete(stream)
                    return CompletableFuture.completedFuture(stream)
                }
            }
        ) {}
        val mux = if (muxer == "mplex") StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        fun newHost(listen: Boolean): Host = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels {
                add { key, _ ->
                    val plaintext = PlaintextInsecureChannel(key)
                    if (!listen) plaintext else object : io.libp2p.core.security.SecureChannel by plaintext {
                        override fun initChannel(ch: io.libp2p.core.P2PChannel, selectedProtocol: String): CompletableFuture<out io.libp2p.core.security.SecureChannel.Session> {
                            val batch = writeBatches[ch] ?: error("Missing per-connection plaintext write batch")
                            ch.pushHandler(object : io.netty.channel.ChannelDuplexHandler() {
                                override fun channelActive(ctx: ChannelHandlerContext) {
                                    ctx.fireChannelActive()
                                    batch.releaseBatch()
                                }
                            })
                            return plaintext.initChannel(ch, selectedProtocol)
                        }
                    }
                }
            }
            muxers { add(mux) }
            if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { add(binding) }
            debug {
                val role = if (listen) "server" else "client"
                fun recorder(stage: String) = object : io.netty.channel.ChannelDuplexHandler() {
                    var activations = 0
                    fun record(event: String) {
                        traceEvents.add("${System.nanoTime()} $role.$stage ${channelId} $event")
                    }
                    var channelId = "unset"
                    override fun handlerAdded(ctx: ChannelHandlerContext) {
                        channelId = ctx.channel().id().asShortText()
                    }
                    override fun channelActive(ctx: ChannelHandlerContext) {
                        activations++
                        record("ACTIVE $activations" + if (activations > 1) "\n" + Exception("Repeated activation").stackTraceToString() else "")
                        ctx.fireChannelActive()
                    }
                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        record("READ " + if (msg is ByteBuf) io.netty.buffer.ByteBufUtil.hexDump(msg) else msg.toString())
                        ctx.fireChannelRead(msg)
                    }
                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: io.netty.channel.ChannelPromise) {
                        record("WRITE " + if (msg is ByteBuf) io.netty.buffer.ByteBufUtil.hexDump(msg) else msg.toString())
                        ctx.write(msg, promise)
                    }
                    override fun close(ctx: ChannelHandlerContext, promise: io.netty.channel.ChannelPromise) {
                        record("CLOSE\n" + Exception("Physical close $role.$stage").stackTraceToString())
                        ctx.close(promise)
                    }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                        record("EXCEPTION\n" + cause.stackTraceToString())
                        ctx.fireExceptionCaught(cause)
                    }
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        record("INACTIVE")
                        ctx.fireChannelInactive()
                    }
                }
                beforeSecureHandler.addHandler { it.pushHandler(recorder("raw")) }
                if (listen) beforeSecureHandler.addHandler {
                    val batch = ForcedPlaintextWriteBatch(true)
                    writeBatches[it] = batch
                    it.pushHandler(batch)
                }
                afterSecureHandler.addHandler { it.pushHandler(recorder("after")) }
                muxFramesHandler.addHandler { it.pushHandler(recorder("frames")) }
            }
        }

        val client = newHost(false)
        val server = newHost(true)
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val sender = client.newStream<Stream>(
                listOf(COALESCED_PAUSE_PROTOCOL),
                server.peerId,
                server.listenAddresses().single()
            ).controller.get(5, TimeUnit.SECONDS)
            val receiver = responder.get(5, TimeUnit.SECONDS) as StreamOverNetty
            val receiverChannel = receiver.nettyChannel
            val receiverParent = receiverChannel.parent()
            assertTrue(receiverChannel.eventLoop() is IoEventLoop, "the child must use a real Netty I/O event loop")
            assertTrue(
                receiverChannel.eventLoop().parent() is MultiThreadIoEventLoopGroup,
                "the child must use the transport's multi-threaded I/O event-loop group"
            )
            assertTrue(
                (receiverChannel.eventLoop() as IoEventLoop).isIoType(NioIoHandler::class.java),
                "the child event loop must use Netty's NIO I/O handler"
            )
            assertFalse(receiverChannel.eventLoop().inEventLoop(), "the test thread must be outside the child event loop")

            // Keep the real TCP receiver from reading until every application write and the remote end
            // have been flushed into the socket. The first callback then blocks while the decoder
            // forwards this batch through the muxer.
            receiverParent.config().setAutoRead(false)
            val senderChannel = (sender as StreamOverNetty).nettyChannel
            outboundPayloads += expectedMessages.map { Unpooled.copiedBuffer(it, CharsetUtil.UTF_8) }
            val writeFutures = senderChannel.eventLoop().submit(Callable {
                val writes = outboundPayloads.map { senderChannel.write(it) }
                senderChannel.flush()
                writes
            }).get(5, TimeUnit.SECONDS)
            writeFutures.forEach { it.get(5, TimeUnit.SECONDS) }
            senderChannel.disconnect().get(5, TimeUnit.SECONDS)
            receiverParent.config().setAutoRead(true)

            assertTrue(firstDeliveryEntered.await(5, TimeUnit.SECONDS), "the first payload did not enter delivery")
            assertTrue(receiverParent.config().isAutoRead, "the parent should still be reading before the child pauses")
            receiverChannel.config().setAutoRead(false)
            assertTrue(
                receiverParent.config().isAutoRead,
                "an off-loop child pause must be reconciled on the blocked parent event loop"
            )

            continueFirstDelivery.countDown()
            assertTrue(firstDeliveryCompleted.await(5, TimeUnit.SECONDS), "the in-flight payload did not complete")
            assertTrue(
                payloadFramesForwarded.await(5, TimeUnit.SECONDS),
                "not every payload frame reached the muxer while the child was paused"
            )
            assertTrue(
                remoteEndFrameForwarded.await(5, TimeUnit.SECONDS),
                "the remote end frame did not reach the muxer while the child was paused"
            )
            assertFalse(receiverParent.config().isAutoRead, "the paused child must stop parent reads")

            val beforeResume = delivered.toList()
            assertEquals(expectedMessages.first(), beforeResume.first())
            assertTrue(remoteEndMarker !in beforeResume, "the deferred remote end must wait for resume")
            assertTrue(
                beforeResume.size <= 2,
                "an off-loop pause may complete the in-flight payload and at most one already-dispatched payload"
            )
            assertEquals(expectedMessages.take(beforeResume.size), beforeResume)

            receiverChannel.config().setAutoRead(true)
            assertTrue(deliveredPayloads.await(5, TimeUnit.SECONDS), "queued payloads were not delivered after resume")
            assertTrue(deliveredRemoteEnd.await(5, TimeUnit.SECONDS), "the deferred remote end was not delivered")
            receiverChannel.eventLoop().submit(Callable {}).get(5, TimeUnit.SECONDS)
            assertEquals(expectedMessages + remoteEndMarker, delivered.toList())
            assertTrue(receiverParent.config().isAutoRead, "parent reads must resume after the child resumes")
        } finally {
            continueFirstDelivery.countDown()
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    server.stop().get(10, TimeUnit.SECONDS)
                } finally {
                    outboundPayloads.forEach { ReferenceCountUtil.safeRelease(it) }
                    println("R241 TRACE $muxer\n" + traceEvents.joinToString("\n"))
                }
            }
        }
    }

}

private class ForcedPlaintextWriteBatch(private val waitForSelection: Boolean) : io.netty.channel.ChannelDuplexHandler() {
    private lateinit var context: ChannelHandlerContext
    private val buffers = mutableListOf<ByteBuf>()
    private val promises = mutableListOf<io.netty.channel.ChannelPromise>()
    private var released = false
    private var holding = !waitForSelection
    var writeCount = 0
        private set
    override fun handlerAdded(ctx: ChannelHandlerContext) {
        context = ctx
    }
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: io.netty.channel.ChannelPromise) {
        if (!holding && msg is ByteBuf && msg.toString(Charsets.UTF_8).contains("/plaintext/2.0.0")) holding = true
        if (released || !holding) {
            ctx.write(msg, promise)
        } else {
            buffers += msg as ByteBuf
            promises += promise
            writeCount++
        }
    }
    override fun flush(ctx: ChannelHandlerContext) {
        if (released || !holding) ctx.flush()
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
        buffers.forEach { ReferenceCountUtil.release(it) }
        buffers.clear()
        promises.forEach { it.tryFailure(io.libp2p.core.ConnectionClosedException()) }
        promises.clear()
        ctx.fireChannelInactive()
    }
}
