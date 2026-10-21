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
import io.netty.channel.SimpleChannelInboundHandler
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
class YamuxPausedStreamKeepsConnectionReadingTest {

    private class Responder(val stream: StreamOverNetty) {
        val received = ByteArrayOutputStream()
        val receivedBytes = java.util.concurrent.atomic.AtomicInteger()

        fun snapshot(): ByteArray = synchronized(received) { received.toByteArray() }
    }

    private val responders = LinkedBlockingQueue<Responder>()

    private val binding = object : StrictProtocolBinding<Stream>(
        PAUSED_STREAM_PROTOCOL,
        object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
            override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> =
                CompletableFuture.completedFuture(stream)

            override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                val responder = Responder(stream as StreamOverNetty)
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

    private fun newHost(listen: Boolean): Host = host {
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

    private fun nextResponder(): Responder =
        responders.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("the server did not accept the expected stream within 5 seconds")

    /** Runs a no-op on [channel]'s event loop so an off-loop autoRead change has been reconciled. */
    private fun settle(channel: Channel) {
        channel.eventLoop().submit(Callable {}).get(5, TimeUnit.SECONDS)
    }

    private fun awaitBytes(responder: Responder, expected: Int, what: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (responder.receivedBytes.get() < expected && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(expected, responder.receivedBytes.get(), "$what: delivered byte count")
    }

    private fun payload(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 31) xor seed).toByte() }

    @Test
    fun pausedStreamDoesNotStopSiblingDataOrNewStreamNegotiation() {
        val client = newHost(false)
        val server = newHost(true)
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)

            val pausedSender = client.openStream(server)
            val paused = nextResponder()
            val pausedChannel = paused.stream.nettyChannel
            val connection = pausedChannel.parent()
            pausedChannel.config().isAutoRead = false
            settle(pausedChannel)

            // Far more than one receive window: the sender must be held back by the window alone.
            val pausedPayload = payload(4 * 1024 * 1024, 0x5a)
            val pausedWrite = pausedSender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(pausedPayload))

            val siblingSender = try {
                client.openStream(server)
            } catch (failure: Exception) {
                throw AssertionError(
                    "a new stream could not complete protocol negotiation while another stream on the same " +
                        "Yamux connection was paused; connection autoRead=${connection.config().isAutoRead}",
                    failure
                )
            }
            val sibling = nextResponder()
            assertTrue(sibling.stream.nettyChannel.parent() === connection, "both streams must share one connection")

            val siblingPayload = payload(64 * 1024, 0x33)
            siblingSender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(siblingPayload)).get(5, TimeUnit.SECONDS)
            awaitBytes(sibling, siblingPayload.size, "sibling stream data while another stream was paused")
            assertArrayEquals(siblingPayload, sibling.snapshot(), "sibling stream data changed")

            settle(connection)
            assertTrue(
                connection.config().isAutoRead,
                "a paused Yamux stream must leave the shared connection reading"
            )
            assertFalse(pausedWrite.isDone, "the paused stream's sender must be held back by its receive window")
            assertEquals(0, paused.receivedBytes.get(), "a paused stream must not receive data before it resumes")

            pausedChannel.config().isAutoRead = true
            pausedWrite.get(10, TimeUnit.SECONDS)
            awaitBytes(paused, pausedPayload.size, "paused stream data after resume")
            assertArrayEquals(pausedPayload, paused.snapshot(), "paused stream data changed or was reordered")
            assertTrue(connection.config().isAutoRead, "the connection must still be reading after the stream resumes")
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun pausedStreamReceivingManySmallFramesStillBoundsItsQueueByPausingTheConnection() {
        val client = newHost(false)
        val server = newHost(true)
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)

            val sender = client.openStream(server)
            val paused = nextResponder()
            val pausedChannel = paused.stream.nettyChannel
            val connection = pausedChannel.parent()
            pausedChannel.config().isAutoRead = false
            settle(pausedChannel)

            // One-byte frames cost the window almost nothing, so the window alone does not bound the
            // number of retained frames. Past a fixed frame count the muxer must stop the connection.
            val frameCount = 1_000
            val writes = (0 until frameCount).map { index ->
                sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(byteArrayOf(index.toByte())))
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (connection.config().isAutoRead && System.nanoTime() < deadline) Thread.sleep(5)
            settle(connection)
            assertFalse(
                connection.config().isAutoRead,
                "a paused stream with many retained small frames must stop reads on its connection"
            )
            assertEquals(0, paused.receivedBytes.get(), "a paused stream must not receive data before it resumes")

            pausedChannel.config().isAutoRead = true
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
            awaitBytes(paused, frameCount, "small frames after resume")
            assertArrayEquals(
                ByteArray(frameCount) { it.toByte() },
                paused.snapshot(),
                "small frames changed or were reordered"
            )
            settle(connection)
            assertTrue(connection.config().isAutoRead, "the connection must read again after the stream resumes")
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun closingAPausedStreamWithQueuedDataKeepsTheConnectionReading() {
        val client = newHost(false)
        val server = newHost(true)
        try {
            client.start().get(5, TimeUnit.SECONDS)
            server.start().get(5, TimeUnit.SECONDS)

            val sender = client.openStream(server)
            val paused = nextResponder()
            val pausedChannel = paused.stream.nettyChannel
            val connection = pausedChannel.parent()
            pausedChannel.config().isAutoRead = false
            settle(pausedChannel)
            sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(payload(100 * 1024, 0x11)))

            val closed = CountDownLatch(1)
            pausedChannel.closeFuture().addListener { closed.countDown() }
            pausedChannel.close()
            assertTrue(closed.await(5, TimeUnit.SECONDS), "the paused stream did not close")

            val nextSender = client.openStream(server)
            val next = nextResponder()
            val nextPayload = payload(32 * 1024, 0x77)
            nextSender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(nextPayload)).get(5, TimeUnit.SECONDS)
            awaitBytes(next, nextPayload.size, "a new stream after a paused stream closed")
            assertArrayEquals(nextPayload, next.snapshot())
            settle(connection)
            assertTrue(connection.config().isAutoRead, "closing a paused stream must leave the connection reading")
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
