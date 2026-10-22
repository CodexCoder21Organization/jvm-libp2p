package io.libp2p.mux.yamux

import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.SimpleChannelInboundHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val PAUSED_STREAM_PROTOCOL = "/test/yamux-paused-stream/1.0.0"

/**
 * A Yamux stream whose reader pauses (child `autoRead = false`) is flow-controlled by its own
 * receive window: the muxer withholds that stream's window credit until the queued bytes are
 * delivered. The pause must therefore not stop reads on the shared physical connection. Other
 * streams on the same connection must keep receiving data, and new streams must still complete
 * protocol negotiation, while the paused stream's sender is held back by its window alone.
 *
 * Observed downstream (UrlProtocol, 2026-10-21): an RPC frame waiting for the server's
 * process-wide parse-memory pool paused its stream; the paused stream stopped the whole TCP
 * connection, so the body of the frame that held the pool on the same connection could not
 * arrive, that stream was closed by its 10 s no-progress guard, and three new streams on the
 * connection failed negotiation with `TotalTimeoutException`.
 */
class YamuxPausedQueueControlTrafficTcpTest {

    private val responders = LinkedBlockingQueue<ControlTrafficResponder>()

    private val binding = object : StrictProtocolBinding<Stream>(
        PAUSED_STREAM_PROTOCOL,
        object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
            override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> =
                CompletableFuture.completedFuture(stream)

            override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                val responder = ControlTrafficResponder(stream as StreamOverNetty)
                stream.nettyChannel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                        val bytes = ByteArray(msg.readableBytes())
                        msg.readBytes(bytes)
                        synchronized(responder.received) { responder.received.write(bytes) }
                        responder.receivedBytes.addAndGet(bytes.size)
                    }
                })
                responders.add(responder)
                return CompletableFuture.completedFuture(stream)
            }
        }
    ) {}

    @ParameterizedTest
    @ValueSource(ints = [64, 1000])
    fun saturatedPausedRequestCompletesItsReplyOrExplicitlyResets(frameCount: Int) {
        val client = newHost(false, binding)
        val server = newHost(true, binding)
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val sender = client.openStream(server) as StreamOverNetty
            val paused = nextControlTrafficResponder(responders)
            val received = java.util.concurrent.atomic.AtomicInteger()
            sender.nettyChannel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                    received.addAndGet(msg.readableBytes())
                }
            })
            val connection = paused.stream.nettyChannel.parent()
            paused.stream.nettyChannel.config().isAutoRead = false
            settle(connection)
            val thresholdRead = CountDownLatch(1)
            connection.eventLoop().submit(
                Callable {
                    var frames = 0
                    connection.pipeline().addBefore(
                        connection.pipeline().context(YamuxHandler::class.java).name(),
                        "pressure-observer",
                        object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                ctx.fireChannelRead(msg)
                                if (msg is YamuxFrame && msg.id == paused.stream.nettyChannel.id() &&
                                    msg.type == YamuxType.DATA && msg.length > 0 && ++frames == minOf(frameCount, 65)
                                ) {
                                    thresholdRead.countDown()
                                }
                            }
                        }
                    )
                }
            ).get(5, TimeUnit.SECONDS)
            val writes = (0 until frameCount).map { index ->
                sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(index.toByte())))
            }
            // A local pressure reset may reject writes not yet handed to TCP.
            writes.forEach { it.handle { _, _ -> Unit }.get(5, TimeUnit.SECONDS) }
            assertTrue(thresholdRead.await(5, TimeUnit.SECONDS), "The pressure boundary must reach the receiver")
            val reply = payload(4 * 1024 * 1024, 0x19)
            val finished = paused.stream.writeAndFlushWithFuture(Unpooled.wrappedBuffer(reply))
            // A real request handler may resume only after this write completes.
            finished.whenComplete { _, _ -> paused.stream.nettyChannel.config().isAutoRead = true }
            try {
                val failure = finished.handle { _, cause -> cause }.get(5, TimeUnit.SECONDS)
                if (failure != null) {
                    assertEquals(1000, frameCount, "64 paused frames must still permit the complete reply")
                    // A bounded receiver may reset this stream, but it must finish the reset.
                    paused.stream.closeFuture().get(5, TimeUnit.SECONDS)
                    sender.closeFuture().get(5, TimeUnit.SECONDS)
                } else {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (received.get() < reply.size && System.nanoTime() < deadline) Thread.sleep(5)
                    assertEquals(reply.size, received.get(), "The successful reply must arrive in full")
                }
                assertTrue(connection.config().isAutoRead, "Control traffic must remain readable at pressure")
            } catch (failure: Exception) {
                throw AssertionError("Reply cannot finish while its request reader is paused: received=${received.get()} expected=${reply.size} parentAutoRead=${connection.config().isAutoRead}", failure)
            }
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [64, 1000])
    fun remoteResetDoesNotWaitForPausedRequestDelivery(frameCount: Int) {
        val client = newHost(false, binding)
        val server = newHost(true, binding)
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)
            val sender = client.openStream(server)
            val paused = nextControlTrafficResponder(responders)
            val connection = paused.stream.nettyChannel.parent()
            paused.stream.nettyChannel.config().isAutoRead = false
            settle(connection)
            val thresholdRead = CountDownLatch(1)
            connection.eventLoop().submit(
                Callable {
                    var frames = 0
                    connection.pipeline().addBefore(
                        connection.pipeline().context(YamuxHandler::class.java).name(),
                        "pressure-observer",
                        object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                ctx.fireChannelRead(msg)
                                if (msg is YamuxFrame && msg.id == paused.stream.nettyChannel.id() &&
                                    msg.type == YamuxType.DATA && msg.length > 0 && ++frames == minOf(frameCount, 65)
                                ) {
                                    thresholdRead.countDown()
                                }
                            }
                        }
                    )
                }
            ).get(5, TimeUnit.SECONDS)
            val writes = (0 until frameCount).map { index ->
                sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(index.toByte())))
            }
            // A local pressure reset may reject writes not yet handed to TCP.
            writes.forEach { it.handle { _, _ -> Unit }.get(5, TimeUnit.SECONDS) }
            assertTrue(thresholdRead.await(5, TimeUnit.SECONDS), "The pressure boundary must reach the receiver")
            sender.close().get(5, TimeUnit.SECONDS)
            try {
                paused.stream.closeFuture().get(5, TimeUnit.SECONDS)
                assertTrue(connection.config().isAutoRead, "Reset must leave the connection reading")
            } catch (failure: Exception) {
                throw AssertionError("Remote reset cannot close the paused stream: parentAutoRead=${connection.config().isAutoRead} childOpen=${paused.stream.nettyChannel.isOpen}", failure)
            }
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}

private class ControlTrafficResponder(val stream: StreamOverNetty) {
    val received = ByteArrayOutputStream()
    val receivedBytes = java.util.concurrent.atomic.AtomicInteger()
}

private fun newHost(listen: Boolean, binding: StrictProtocolBinding<Stream>): Host = host {
    identity { random() }
    transports { +::TcpTransport }
    secureChannels { add(::PlaintextInsecureChannel) }
    muxers { add(StreamMuxerProtocol.getYamux()) }
    if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
    protocols { add(binding) }
}

private fun Host.openStream(server: Host): Stream =
    newStream<Stream>(listOf(PAUSED_STREAM_PROTOCOL), server.peerId, server.listenAddresses().single())
        .controller.get(5, TimeUnit.SECONDS)

private fun nextControlTrafficResponder(responders: LinkedBlockingQueue<ControlTrafficResponder>): ControlTrafficResponder =
    responders.poll(5, TimeUnit.SECONDS)
        ?: throw AssertionError("the server did not accept the expected stream within 5 seconds")

/** Runs a no-op on [channel]'s event loop so an off-loop autoRead change has been reconciled. */
private fun settle(channel: Channel) {
    channel.eventLoop().submit(Callable {}).get(5, TimeUnit.SECONDS)
}

private fun payload(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 31) xor seed).toByte() }
