package io.libp2p.mux

import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.mux.mplex.MplexFlag
import io.libp2p.mux.mplex.MplexFrame
import io.libp2p.mux.yamux.INITIAL_WINDOW_SIZE
import io.libp2p.mux.yamux.YamuxFrame
import io.libp2p.mux.yamux.YamuxType
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val SIBLING_PROTOCOL = "/test/child-pause-sibling/1.0.0"

/** Bytes moved on the sibling stream: four full default Yamux receive windows plus one byte. */
private const val SIBLING_TRANSFER_BYTES = 4 * INITIAL_WINDOW_SIZE + 1

/**
 * Real TCP connections between two hosts. One server-side child stream is paused while a sibling
 * stream on the same connection transfers far more than one receive window.
 *
 * Yamux has per-stream flow control, so a paused child only withholds its own receive credit and
 * its own delivery; the sibling and the parent connection keep reading. Mplex has no flow control,
 * so a paused child still stops reads for the whole connection, holding the sibling until resume.
 */
class ChildPauseSiblingStreamTcpTest {
    private class Receiver(val stream: StreamOverNetty) {
        private val lock = Any()
        private val bytes = ByteArrayOutputStream()
        private val waiters = mutableListOf<Pair<Int, CountDownLatch>>()

        fun add(msg: ByteBuf) {
            val chunk = ByteArray(msg.readableBytes())
            msg.readBytes(chunk)
            synchronized(lock) {
                bytes.write(chunk)
                waiters.filter { bytes.size() >= it.first }.forEach { it.second.countDown() }
            }
        }

        fun size(): Int = synchronized(lock) { bytes.size() }
        fun content(): ByteArray = synchronized(lock) { bytes.toByteArray() }
        fun awaitAtLeast(count: Int, seconds: Long): Boolean {
            val latch = CountDownLatch(1)
            synchronized(lock) {
                if (bytes.size() >= count) return true
                waiters += count to latch
            }
            return latch.await(seconds, TimeUnit.SECONDS)
        }
    }

    private val receivers = LinkedBlockingQueue<Receiver>()
    private val hosts = mutableListOf<Host>()

    private val binding = object : StrictProtocolBinding<Stream>(
        SIBLING_PROTOCOL,
        object : ProtocolHandler<Stream>(Long.MAX_VALUE, Long.MAX_VALUE) {
            override fun onStartInitiator(stream: Stream): CompletableFuture<Stream> =
                CompletableFuture.completedFuture(stream)

            override fun onStartResponder(stream: Stream): CompletableFuture<Stream> {
                val receiver = Receiver(stream as StreamOverNetty)
                stream.nettyChannel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) = receiver.add(msg)
                })
                receivers.add(receiver)
                return CompletableFuture.completedFuture(stream)
            }
        }
    ) {}

    private fun newHost(mux: StreamMuxerProtocol, listen: Boolean): Host = host {
        identity { random() }
        transports { +::TcpTransport }
        secureChannels { add(::PlaintextInsecureChannel) }
        muxers { add(mux) }
        if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { add(binding) }
    }.also { hosts += it }

    @AfterEach
    fun stopHosts() {
        var failure: Throwable? = null
        hosts.forEach { host ->
            try {
                host.stop().get(10, TimeUnit.SECONDS)
            } catch (cause: Throwable) {
                if (failure == null) failure = cause else failure!!.addSuppressed(cause)
            }
        }
        hosts.clear()
        failure?.let { throw it }
    }

    private class StreamPair(val sender: StreamOverNetty, val receiver: Receiver)

    private fun connectedPair(mux: StreamMuxerProtocol): Pair<Host, Host> {
        val client = newHost(mux, false)
        val server = newHost(mux, true)
        client.start().get(5, TimeUnit.SECONDS)
        server.start().get(5, TimeUnit.SECONDS)
        return client to server
    }

    private fun openStream(client: Host, server: Host): StreamPair {
        val sender = client.newStream<Stream>(
            listOf(SIBLING_PROTOCOL),
            server.peerId,
            server.listenAddresses().single()
        ).controller.get(5, TimeUnit.SECONDS) as StreamOverNetty
        val receiver = receivers.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the server did not accept the stream")
        return StreamPair(sender, receiver)
    }

    private fun patterned(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it + seed) % 251).toByte() }

    /** Runs a no-op on [channel]'s event loop so off-loop config changes have been reconciled. */
    private fun syncLoop(channel: Channel) = channel.eventLoop().submit(Callable {}).get(5, TimeUnit.SECONDS)

    /**
     * Counts positive WINDOW_UPDATE credit that arrives at the client for each Yamux stream id.
     * Installed on the client's parent pipeline, in front of the muxer.
     */
    private fun countClientWindowCredit(clientParent: Channel): ConcurrentHashMap<Long, AtomicLong> {
        val credit = ConcurrentHashMap<Long, AtomicLong>()
        val muxContext = clientParent.pipeline().context(MuxHandler::class.java)
        clientParent.eventLoop().submit(
            Callable {
                clientParent.pipeline().addBefore(
                    muxContext.name(),
                    "count-window-credit",
                    object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is YamuxFrame && msg.type == YamuxType.WINDOW_UPDATE && msg.length > 0) {
                                credit.computeIfAbsent(msg.id.id) { AtomicLong() }.addAndGet(msg.length)
                            }
                            ctx.fireChannelRead(msg)
                        }
                    }
                )
            }
        ).get(5, TimeUnit.SECONDS)
        return credit
    }

    /** Counts payload bytes for each stream id that the server's parent pipeline forwards into the muxer. */
    private fun countServerForwardedBytes(serverParent: Channel): ConcurrentHashMap<Long, AtomicLong> {
        val forwarded = ConcurrentHashMap<Long, AtomicLong>()
        val muxContext = serverParent.pipeline().context(MuxHandler::class.java)
        serverParent.eventLoop().submit(
            Callable {
                serverParent.pipeline().addBefore(
                    muxContext.name(),
                    "count-forwarded-bytes",
                    object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            val (id, size) = when {
                                msg is YamuxFrame && msg.type == YamuxType.DATA -> msg.id.id to (msg.data?.readableBytes() ?: 0)
                                msg is MplexFrame && msg.flag.type == MplexFlag.Type.DATA -> msg.id.id to msg.data.readableBytes()
                                else -> -1L to 0
                            }
                            try {
                                ctx.fireChannelRead(msg)
                            } finally {
                                if (size > 0) forwarded.computeIfAbsent(id) { AtomicLong() }.addAndGet(size.toLong())
                            }
                        }
                    }
                )
            }
        ).get(5, TimeUnit.SECONDS)
        return forwarded
    }

    private val StreamOverNetty.muxId get() = (nettyChannel as MuxChannel<*>).id.id

    @Test
    fun yamuxPausedChildDoesNotStallSiblingStreamOnTheSameConnection() {
        val (client, server) = connectedPair(StreamMuxerProtocol.getYamux())
        val paused = openStream(client, server)
        val sibling = openStream(client, server)
        val serverParent = paused.receiver.stream.nettyChannel.parent()
        assertSame(serverParent, sibling.receiver.stream.nettyChannel.parent(), "both streams must share one connection")
        assertTrue(serverParent.config().isAutoRead)
        val credit = countClientWindowCredit(paused.sender.nettyChannel.parent())

        paused.receiver.stream.nettyChannel.config().isAutoRead = false
        syncLoop(serverParent)

        val siblingPayload = patterned(SIBLING_TRANSFER_BYTES, 7)
        sibling.sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(siblingPayload))
        assertTrue(
            sibling.receiver.awaitAtLeast(SIBLING_TRANSFER_BYTES, 5),
            "the sibling stream stalled after ${sibling.receiver.size()} of $SIBLING_TRANSFER_BYTES bytes while another " +
                "child on the same Yamux connection was paused"
        )
        assertArrayEquals(siblingPayload, sibling.receiver.content())
        assertEquals(0, paused.receiver.size())
        assertFalse(paused.receiver.stream.nettyChannel.config().isAutoRead)
        assertTrue(serverParent.config().isAutoRead, "a paused Yamux child must not stop parent connection reads")
        assertEquals(0L, credit[paused.sender.muxId]?.get() ?: 0L, "a paused child must not return receive credit")
    }

    @Test
    fun yamuxPausedChildAtItsFullWindowWithholdsOnlyItsOwnCreditAndResumesInOrder() {
        val (client, server) = connectedPair(StreamMuxerProtocol.getYamux())
        val paused = openStream(client, server)
        val sibling = openStream(client, server)
        val serverParent = paused.receiver.stream.nettyChannel.parent()
        val credit = countClientWindowCredit(paused.sender.nettyChannel.parent())
        val forwarded = countServerForwardedBytes(serverParent)

        paused.receiver.stream.nettyChannel.config().isAutoRead = false
        syncLoop(serverParent)

        // More than the paused child's whole receive window. The sender may send only the credit it
        // still holds (the window minus the bytes protocol negotiation used); the rest must wait.
        val pausedPayload = patterned(INITIAL_WINDOW_SIZE + 1, 3)
        val pausedWrite = paused.sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(pausedPayload))

        // Written after the paused stream's admissible frames on the same connection, so once the
        // sibling has everything, the paused stream's frames have reached the server muxer too.
        val siblingPayload = patterned(SIBLING_TRANSFER_BYTES, 11)
        sibling.sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(siblingPayload))
        assertTrue(
            sibling.receiver.awaitAtLeast(SIBLING_TRANSFER_BYTES, 5),
            "the sibling stream stalled after ${sibling.receiver.size()} of $SIBLING_TRANSFER_BYTES bytes while a " +
                "child holding its full receive window was paused"
        )
        assertArrayEquals(siblingPayload, sibling.receiver.content())
        syncLoop(serverParent)
        val queuedBytes = forwarded[paused.sender.muxId]?.get() ?: 0L
        assertTrue(
            queuedBytes > INITIAL_WINDOW_SIZE - 1024L && queuedBytes <= INITIAL_WINDOW_SIZE,
            "the paused child should hold its remaining receive window and no more, but held $queuedBytes bytes"
        )
        assertEquals(0, paused.receiver.size())
        assertFalse(pausedWrite.isDone, "the sender must not get credit past the paused child's window")
        assertEquals(0L, credit[paused.sender.muxId]?.get() ?: 0L, "a paused child must not return receive credit")
        assertTrue(serverParent.config().isAutoRead)

        paused.receiver.stream.nettyChannel.config().isAutoRead = true
        pausedWrite.get(5, TimeUnit.SECONDS)
        assertTrue(
            paused.receiver.awaitAtLeast(pausedPayload.size, 5),
            "queued payload was not delivered after resume (${paused.receiver.size()} of ${pausedPayload.size} bytes)"
        )
        assertArrayEquals(pausedPayload, paused.receiver.content())
        assertTrue((credit[paused.sender.muxId]?.get() ?: 0L) > 0L, "resume must return receive credit")
    }

    @Test
    fun yamuxConcurrentPauseResumeOfOneChildNeverStallsSiblingAndKeepsItsOwnOrder() {
        val (client, server) = connectedPair(StreamMuxerProtocol.getYamux())
        val toggled = openStream(client, server)
        val sibling = openStream(client, server)
        val serverParent = toggled.receiver.stream.nettyChannel.parent()
        val config = toggled.receiver.stream.nettyChannel.config()

        val toggledPayload = patterned(SIBLING_TRANSFER_BYTES, 13)
        val toggledWrite = toggled.sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(toggledPayload))
        val firstSiblingPayload = patterned(SIBLING_TRANSFER_BYTES, 17)
        sibling.sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(firstSiblingPayload))

        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val togglers = List(4) {
                workers.submit {
                    start.await()
                    repeat(2000) { index -> config.isAutoRead = index % 2 == 0 }
                }
            }
            start.countDown()
            togglers.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
        config.isAutoRead = false
        syncLoop(serverParent)

        val secondSiblingPayload = patterned(SIBLING_TRANSFER_BYTES, 19)
        sibling.sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(secondSiblingPayload))
        val siblingTotal = 2 * SIBLING_TRANSFER_BYTES
        assertTrue(
            sibling.receiver.awaitAtLeast(siblingTotal, 5),
            "the sibling stream stalled after ${sibling.receiver.size()} of $siblingTotal bytes while the other " +
                "child was paused by racing callers"
        )
        assertArrayEquals(firstSiblingPayload + secondSiblingPayload, sibling.receiver.content())
        assertTrue(serverParent.config().isAutoRead, "racing child pauses must never stop Yamux parent reads")
        val beforeResume = toggled.receiver.content()
        assertArrayEquals(toggledPayload.copyOf(beforeResume.size), beforeResume, "the toggled child's data must stay in order")

        config.isAutoRead = true
        assertTrue(
            toggled.receiver.awaitAtLeast(SIBLING_TRANSFER_BYTES, 5),
            "the toggled child received ${toggled.receiver.size()} of $SIBLING_TRANSFER_BYTES bytes after resume"
        )
        assertArrayEquals(toggledPayload, toggled.receiver.content())
        toggledWrite.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun mplexPausedChildStillHoldsSiblingStreamUntilResume() {
        val (client, server) = connectedPair(StreamMuxerProtocol.Mplex)
        val paused = openStream(client, server)
        val sibling = openStream(client, server)
        val serverParent = paused.receiver.stream.nettyChannel.parent()
        assertSame(serverParent, sibling.receiver.stream.nettyChannel.parent(), "both streams must share one connection")
        assertTrue(serverParent.config().isAutoRead)

        paused.receiver.stream.nettyChannel.config().isAutoRead = false
        syncLoop(serverParent)
        assertFalse(serverParent.config().isAutoRead, "a paused Mplex child must stop parent connection reads")

        // A first sibling chunk that the kernel accepts at once; the paused parent must not read it.
        val firstChunk = patterned(1024, 5)
        sibling.sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(firstChunk)).get(5, TimeUnit.SECONDS)
        val rest = patterned(SIBLING_TRANSFER_BYTES - firstChunk.size, 5 + firstChunk.size)
        sibling.sender.writeAndFlushWithFuture(Unpooled.wrappedBuffer(rest))
        syncLoop(serverParent)
        assertEquals(0, sibling.receiver.size(), "Mplex has no per-stream flow control, so the sibling is held")
        assertFalse(serverParent.config().isAutoRead)

        paused.receiver.stream.nettyChannel.config().isAutoRead = true
        assertTrue(
            sibling.receiver.awaitAtLeast(SIBLING_TRANSFER_BYTES, 5),
            "the sibling stream did not resume after the paused child resumed (${sibling.receiver.size()} bytes)"
        )
        assertArrayEquals(firstChunk + rest, sibling.receiver.content())
        syncLoop(serverParent)
        assertTrue(serverParent.config().isAutoRead, "parent reads must resume after the paused child resumes")
    }
}
