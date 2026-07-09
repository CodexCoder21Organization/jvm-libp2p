package io.libp2p.transport

import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.types.toByteArray
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlin.math.max

private const val BULK_PROTOCOL_ID = "/test/outbound-buffer-backpressure/1.0.0"
private const val MAX_ALLOWED_PENDING_BYTES = 16 * 1024 * 1024L
private const val STALLED_STREAM_COUNT = 256
private const val STALLED_CHUNK_BYTES = 256 * 1024
private const val DRAINING_TOTAL_BYTES = 8 * 1024 * 1024
private const val DRAINING_CHUNK_BYTES = 64 * 1024

class NettyOutboundBufferBackpressureTest {

    @Test
    fun stalledTcpReceiverKeepsSenderParentOutboundBufferBounded() {
        val protocol = BulkWriteProtocol(expectedBytes = Long.MAX_VALUE)
        val clientHost = createHost(protocol, listen = false)
        val serverHost = createHost(protocol, listen = true)

        try {
            clientHost.start().get(5, TimeUnit.SECONDS)
            serverHost.start().get(5, TimeUnit.SECONDS)
            val serverAddress = serverHost.listenAddresses().single()

            val streams = (0 until STALLED_STREAM_COUNT).map {
                clientHost.newStream<BulkWriteController>(
                    listOf(BULK_PROTOCOL_ID),
                    serverHost.peerId,
                    serverAddress
                ).stream.get(5, TimeUnit.SECONDS)
            }
            protocol.awaitResponderStreams(STALLED_STREAM_COUNT)

            val clientParent = parentChannel(streams.first())
            val serverParent = parentChannel(serverHost.network.connections.single() as ConnectionOverNetty)
            configureSmallSocketBuffers(clientParent, serverParent)
            stopSocketReads(serverParent)

            val chunk = ByteArray(STALLED_CHUNK_BYTES) { (it and 0xff).toByte() }
            var peakPendingBytes = pendingOutboundBytes(clientParent)

            streams.forEachIndexed { index, stream ->
                stream.writeAndFlush(Unpooled.wrappedBuffer(chunk))
                if ((index + 1) % 8 == 0) {
                    peakPendingBytes = max(peakPendingBytes, pendingOutboundBytes(clientParent))
                }
            }

            val writtenBytes = STALLED_STREAM_COUNT.toLong() * STALLED_CHUNK_BYTES
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (
                peakPendingBytes <= MAX_ALLOWED_PENDING_BYTES &&
                clientParent.isActive &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(25)
                peakPendingBytes = max(peakPendingBytes, pendingOutboundBytes(clientParent))
            }

            assertTrue(
                peakPendingBytes <= MAX_ALLOWED_PENDING_BYTES,
                "Expected stalled TCP receiver to keep sender parent pending outbound bytes <= " +
                    "$MAX_ALLOWED_PENDING_BYTES, or close/reset before exceeding that bound. " +
                    "Instead peak pending outbound bytes reached $peakPendingBytes after writing " +
                    "$writtenBytes bytes across $STALLED_STREAM_COUNT Yamux streams; " +
                    "channelActive=${clientParent.isActive}, channelWritable=${clientParent.isWritable}, " +
                    "bytesBeforeWritable=${clientParent.bytesBeforeWritable()}, " +
                    "bytesBeforeUnwritable=${clientParent.bytesBeforeUnwritable()}."
            )
        } finally {
            clientHost.stop().get(5, TimeUnit.SECONDS)
            serverHost.stop().get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun normallyDrainingYamuxPeerReceivesBulkPayloadIntact() {
        val received = CompletableFuture<ReceivedPayload>()
        val protocol = BulkWriteProtocol(expectedBytes = DRAINING_TOTAL_BYTES.toLong(), received = received)
        val clientHost = createHost(protocol, listen = false)
        val serverHost = createHost(protocol, listen = true)

        try {
            clientHost.start().get(5, TimeUnit.SECONDS)
            serverHost.start().get(5, TimeUnit.SECONDS)
            val serverAddress = serverHost.listenAddresses().single()
            val stream = clientHost.newStream<BulkWriteController>(
                listOf(BULK_PROTOCOL_ID),
                serverHost.peerId,
                serverAddress
            ).stream.get(5, TimeUnit.SECONDS)
            protocol.awaitResponderStreams(1)

            val expectedCrc = CRC32()
            var writtenBytes = 0
            while (writtenBytes < DRAINING_TOTAL_BYTES) {
                val size = minOf(DRAINING_CHUNK_BYTES, DRAINING_TOTAL_BYTES - writtenBytes)
                val chunk = ByteArray(size) { ((writtenBytes + it) and 0xff).toByte() }
                expectedCrc.update(chunk)
                stream.writeAndFlush(Unpooled.wrappedBuffer(chunk))
                writtenBytes += size
            }

            val actual = received.get(10, TimeUnit.SECONDS)
            assertEquals(DRAINING_TOTAL_BYTES.toLong(), actual.bytes)
            assertEquals(expectedCrc.value, actual.crc)
        } finally {
            clientHost.stop().get(5, TimeUnit.SECONDS)
            serverHost.stop().get(5, TimeUnit.SECONDS)
        }
    }

    private fun createHost(protocol: BulkWriteProtocol, listen: Boolean): Host {
        @Suppress("UNCHECKED_CAST")
        val binding = protocol as ProtocolBinding<Any>
        return host {
            identity {
                random()
            }
            transports {
                +::TcpTransport
            }
            secureChannels {
                add(::PlaintextInsecureChannel)
            }
            muxers {
                +StreamMuxerProtocol.getYamux(ackBacklogLimit = STALLED_STREAM_COUNT * 2)
            }
            if (listen) {
                network {
                    listen("/ip4/127.0.0.1/tcp/0")
                }
            }
            protocols {
                +binding
            }
        }
    }

    private fun parentChannel(stream: Stream): Channel =
        parentChannel(stream.connection as ConnectionOverNetty)

    private fun parentChannel(connection: ConnectionOverNetty): Channel =
        connection.nettyChannel

    private fun configureSmallSocketBuffers(clientParent: Channel, serverParent: Channel) {
        clientParent.eventLoop().submit {
            clientParent.config().setOption(ChannelOption.SO_SNDBUF, 4096)
        }.get(5, TimeUnit.SECONDS)
        serverParent.eventLoop().submit {
            serverParent.config().setOption(ChannelOption.SO_RCVBUF, 4096)
        }.get(5, TimeUnit.SECONDS)
    }

    private fun stopSocketReads(channel: Channel) {
        channel.eventLoop().submit {
            channel.config().setAutoRead(false)
        }.get(5, TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    private fun pendingOutboundBytes(channel: Channel): Long =
        channel.eventLoop().submit<Long> {
            channel.unsafe().outboundBuffer()?.totalPendingWriteBytes() ?: 0L
        }.get(5, TimeUnit.SECONDS)
}

private data class ReceivedPayload(val bytes: Long, val crc: Long)

private class BulkWriteProtocol(
    private val expectedBytes: Long,
    private val received: CompletableFuture<ReceivedPayload> = CompletableFuture(),
    private val responderStreams: AtomicInteger = AtomicInteger()
) : StrictProtocolBinding<BulkWriteController>(
    BULK_PROTOCOL_ID,
    BulkWriteProtocolHandler(expectedBytes, received, responderStreams)
) {
    fun awaitResponderStreams(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (responderStreams.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(expected, responderStreams.get(), "Responder did not negotiate every expected stream")
    }
}

private class BulkWriteProtocolHandler(
    private val expectedBytes: Long,
    private val received: CompletableFuture<ReceivedPayload>,
    private val responderStreams: AtomicInteger
) : ProtocolHandler<BulkWriteController>(Long.MAX_VALUE, Long.MAX_VALUE) {

    override fun onStartInitiator(stream: Stream): CompletableFuture<BulkWriteController> =
        CompletableFuture.completedFuture(BulkWriteController(stream))

    override fun onStartResponder(stream: Stream): CompletableFuture<BulkWriteController> {
        responderStreams.incrementAndGet()
        stream.pushHandler(CountingInboundHandler(expectedBytes, received))
        return CompletableFuture.completedFuture(BulkWriteController(stream))
    }
}

private class BulkWriteController(private val stream: Stream) {
    fun write(bytes: ByteArray) {
        stream.writeAndFlush(Unpooled.wrappedBuffer(bytes))
    }
}

private class CountingInboundHandler(
    private val expectedBytes: Long,
    private val received: CompletableFuture<ReceivedPayload>
) : SimpleChannelInboundHandler<ByteBuf>() {
    private val crc = CRC32()
    private var bytes = 0L

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        val data = msg.toByteArray()
        bytes += data.size
        crc.update(data)
        if (bytes >= expectedBytes && !received.isDone) {
            received.complete(ReceivedPayload(bytes, crc.value))
        }
    }
}
