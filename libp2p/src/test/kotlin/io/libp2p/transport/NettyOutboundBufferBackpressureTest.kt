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
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlin.math.max

private const val BULK_PROTOCOL_ID = "/test/outbound-buffer-backpressure/1.0.0"
private const val MAX_ALLOWED_PENDING_BYTES = 16 * 1024 * 1024L
private const val PARENT_OUTBOUND_BUDGET_BYTES = 10 * 1024 * 1024
private const val STALLED_STREAM_COUNT = 256
private const val STALLED_CHUNK_BYTES = 256 * 1024
private const val DRAINING_TOTAL_BYTES = 8 * 1024 * 1024
private const val DRAINING_CHUNK_BYTES = 64 * 1024
private const val RECOVERY_STREAM_COUNT = 1
private val PAYLOAD_PREAMBLE = "libp2p-outbound-buffer-test-payload\n".toByteArray(Charsets.US_ASCII)

class NettyOutboundBufferBackpressureTest {

    @Test
    fun stalledTcpReceiverKeepsSenderParentOutboundBufferBounded() {
        val protocol = BulkWriteProtocol(expectedBytes = Long.MAX_VALUE)
        val clientHost = createHost(protocol, listen = false)
        val serverHost = createHost(protocol, listen = true)

        var primaryFailure: Throwable? = null
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
            val socketBuffers = configureSmallSocketBuffers(clientParent, serverParent)
            stopSocketReads(serverParent)

            val chunk = ByteArray(STALLED_CHUNK_BYTES) { (it and 0xff).toByte() }
            var peakPendingBytes = pendingOutboundBytes(clientParent)
            val writeFutures = mutableListOf<CompletableFuture<Unit>>()
            val writtenBuffers = mutableListOf<ByteBuf>()

            streams.forEachIndexed { index, stream ->
                val buffer = Unpooled.wrappedBuffer(chunk.copyOf())
                writtenBuffers += buffer
                writeFutures += stream.writeAndFlushWithFuture(buffer)
                peakPendingBytes = max(peakPendingBytes, pendingOutboundBytes(clientParent))
                assertTrue(
                    peakPendingBytes <= MAX_ALLOWED_PENDING_BYTES,
                    stalledPendingBytesMessage(peakPendingBytes, index + 1, clientParent, socketBuffers)
                )
                if (writeFutures.any { completedFailure(it) != null } || !clientParent.isActive) return@forEachIndexed
            }

            val writtenBytes = STALLED_STREAM_COUNT.toLong() * STALLED_CHUNK_BYTES
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (
                writeFutures.none { completedFailure(it) != null } &&
                clientParent.isActive &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(25)
                peakPendingBytes = max(peakPendingBytes, pendingOutboundBytes(clientParent))
                assertTrue(
                    peakPendingBytes <= MAX_ALLOWED_PENDING_BYTES,
                    stalledPendingBytesMessage(peakPendingBytes, writeFutures.size, clientParent, socketBuffers)
                )
            }

            val budgetFailure = writeFutures.mapNotNull { completedFailure(it) }
                .firstOrNull { it.message?.startsWith("Yamux parent outbound buffer exceeded configured budget;") == true }
                ?: fail(
                    "Expected a writer-visible Yamux parent outbound-buffer failure after writing " +
                        "$writtenBytes bytes across $STALLED_STREAM_COUNT streams; " +
                        "futureFailures=${writeFutures.mapNotNull { completedFailure(it)?.javaClass?.name }}; " +
                        "channelActive=${clientParent.isActive}."
                )
            assertYamuxBudgetFailureMessage(budgetFailure.message!!, serverHost.peerId.toString())
            assertTrue(
                peakPendingBytes <= MAX_ALLOWED_PENDING_BYTES,
                stalledPendingBytesMessage(peakPendingBytes, writeFutures.size, clientParent, socketBuffers)
            )
            waitForCondition("client parent channel to close after budget failure") { !clientParent.isActive }
            waitForCondition("client parent outbound buffer to drain after forced close") {
                pendingOutboundBytes(clientParent) == 0L
            }
            writeFutures.forEach { awaitFutureDone(it) }
            assertTrue(writeFutures.any { completedFailure(it) != null }, "At least one write future must fail visibly")
            writtenBuffers.forEachIndexed { index, buffer ->
                assertEquals(0, buffer.refCnt(), "write buffer $index was not released after forced close")
            }
        } catch (cause: Throwable) {
            primaryFailure = cause
            throw cause
        } finally {
            stopHostsPreservingFailure(primaryFailure, clientHost, serverHost)
        }
    }

    @Test
    fun stalledTcpReceiverFailsInFlightAndSubsequentWritesWithDescriptiveException() {
        val protocol = BulkWriteProtocol(expectedBytes = Long.MAX_VALUE)
        val clientHost = createHost(protocol, listen = false)
        val serverHost = createHost(protocol, listen = true)

        var primaryFailure: Throwable? = null
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

            val chunk = ByteArray(STALLED_CHUNK_BYTES) { 7 }
            val futures = streams.map { stream ->
                stream.writeAndFlushWithFuture(Unpooled.wrappedBuffer(chunk.copyOf()))
            }
            val budgetFailure = awaitMatchingFailure(futures) {
                it.message?.startsWith("Yamux parent outbound buffer exceeded configured budget;") == true
            }
            assertYamuxBudgetFailureMessage(budgetFailure.message!!, serverHost.peerId.toString())
            val subsequentFailureFuture = streams.first().writeAndFlushWithFuture(Unpooled.wrappedBuffer(chunk.copyOf()))
            val subsequentFailure = awaitFailure(subsequentFailureFuture)
            assertTrue(
                subsequentFailure.message == budgetFailure.message || subsequentFailure.message?.contains("closed") == true,
                "Expected subsequent write to fail after the budget-triggered close. " +
                    "First failure='${budgetFailure.message}', subsequent failure='${subsequentFailure.message}'."
            )
        } catch (cause: Throwable) {
            primaryFailure = cause
            throw cause
        } finally {
            stopHostsPreservingFailure(primaryFailure, clientHost, serverHost)
        }
    }

    @Test
    fun normallyDrainingYamuxPeerReceivesBulkPayloadIntact() {
        val received = CompletableFuture<ReceivedPayload>()
        val protocol = BulkWriteProtocol(expectedBytes = DRAINING_TOTAL_BYTES.toLong(), received = received)
        val clientHost = createHost(protocol, listen = false)
        val serverHost = createHost(protocol, listen = true)

        var primaryFailure: Throwable? = null
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
            val writeFutures = mutableListOf<CompletableFuture<Unit>>()
            writeFutures += stream.writeAndFlushWithFuture(Unpooled.wrappedBuffer(PAYLOAD_PREAMBLE.copyOf()))
            while (writtenBytes < DRAINING_TOTAL_BYTES) {
                val size = minOf(DRAINING_CHUNK_BYTES, DRAINING_TOTAL_BYTES - writtenBytes)
                val chunk = ByteArray(size) { ((writtenBytes + it) and 0xff).toByte() }
                expectedCrc.update(chunk)
                val buffer = Unpooled.wrappedBuffer(chunk)
                writeFutures += stream.writeAndFlushWithFuture(buffer)
                writtenBytes += size
            }

            CompletableFuture.allOf(*writeFutures.toTypedArray()).get(10, TimeUnit.SECONDS)
            val actual = received.get(10, TimeUnit.SECONDS)
            assertEquals(DRAINING_TOTAL_BYTES.toLong(), actual.bytes)
            assertEquals(expectedCrc.value, actual.crc)
        } catch (cause: Throwable) {
            primaryFailure = cause
            throw cause
        } finally {
            stopHostsPreservingFailure(primaryFailure, clientHost, serverHost)
        }
    }

    @Test
    fun peerThatStallsBelowBudgetThenResumesReceivesEveryAcceptedByte() {
        val expectedBytes = RECOVERY_STREAM_COUNT.toLong() * STALLED_CHUNK_BYTES
        val received = CompletableFuture<ReceivedPayload>()
        val protocol = BulkWriteProtocol(expectedBytes = expectedBytes, received = received)
        val clientHost = createHost(protocol, listen = false)
        val serverHost = createHost(protocol, listen = true)

        var primaryFailure: Throwable? = null
        try {
            clientHost.start().get(5, TimeUnit.SECONDS)
            serverHost.start().get(5, TimeUnit.SECONDS)
            val serverAddress = serverHost.listenAddresses().single()
            val streams = (0 until RECOVERY_STREAM_COUNT).map {
                clientHost.newStream<BulkWriteController>(
                    listOf(BULK_PROTOCOL_ID),
                    serverHost.peerId,
                    serverAddress
                ).stream.get(5, TimeUnit.SECONDS)
            }
            protocol.awaitResponderStreams(RECOVERY_STREAM_COUNT)

            val clientParent = parentChannel(streams.first())
            val serverParent = parentChannel(serverHost.network.connections.single() as ConnectionOverNetty)
            configureSmallSocketBuffers(clientParent, serverParent)
            streams.first().writeAndFlushWithFuture(Unpooled.wrappedBuffer(PAYLOAD_PREAMBLE.copyOf())).get(10, TimeUnit.SECONDS)
            stopSocketReads(serverParent)

            val chunk = ByteArray(STALLED_CHUNK_BYTES)
            val expectedCrc = CRC32().apply { update(ByteArray(expectedBytes.toInt())) }.value
            val buffers = streams.map { Unpooled.wrappedBuffer(chunk.copyOf()) }
            val futures = streams.zip(buffers).map { (stream, buffer) -> stream.writeAndFlushWithFuture(buffer) }

            waitForCondition("sender parent outbound queue to show the below-budget stall") {
                pendingOutboundBytes(clientParent) > 0L || !clientParent.isWritable
            }
            assertTrue(clientParent.isActive, "Connection should survive a below-budget temporary stall")
            assertTrue(
                pendingOutboundBytes(clientParent) <= MAX_ALLOWED_PENDING_BYTES,
                "Below-budget temporary stall exceeded the regression bound before reads resumed"
            )

            resumeSocketReads(serverParent)

            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            val actual = received.get(10, TimeUnit.SECONDS)
            assertEquals(expectedBytes, actual.bytes)
            assertEquals(expectedCrc, actual.crc)
            assertTrue(clientParent.isActive, "Connection should remain active after the peer resumes draining")
        } catch (cause: Throwable) {
            primaryFailure = cause
            throw cause
        } finally {
            stopHostsPreservingFailure(primaryFailure, clientHost, serverHost)
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
                +StreamMuxerProtocol.getYamux(
                    maxBufferedConnectionWrites = PARENT_OUTBOUND_BUDGET_BYTES,
                    ackBacklogLimit = STALLED_STREAM_COUNT * 2
                )
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

    private fun configureSmallSocketBuffers(clientParent: Channel, serverParent: Channel): SocketBuffers {
        val actualSendBuffer = clientParent.eventLoop().submit<Int> {
            clientParent.config().setOption(ChannelOption.SO_SNDBUF, 4096)
            clientParent.config().getOption(ChannelOption.SO_SNDBUF)
        }.get(5, TimeUnit.SECONDS)
        val actualReceiveBuffer = serverParent.eventLoop().submit<Int> {
            serverParent.config().setOption(ChannelOption.SO_RCVBUF, 4096)
            serverParent.config().getOption(ChannelOption.SO_RCVBUF)
        }.get(5, TimeUnit.SECONDS)
        assertTrue(
            actualSendBuffer in 1..(1024 * 1024),
            "Test setup could not constrain SO_SNDBUF enough to exercise sender-side Netty backpressure; " +
                "requested=4096, actual=$actualSendBuffer"
        )
        assertTrue(
            actualReceiveBuffer in 1..(1024 * 1024),
            "Test setup could not constrain SO_RCVBUF enough to exercise sender-side Netty backpressure; " +
                "requested=4096, actual=$actualReceiveBuffer"
        )
        return SocketBuffers(actualSendBuffer, actualReceiveBuffer)
    }

    private fun stopSocketReads(channel: Channel) {
        channel.eventLoop().submit {
            channel.config().setAutoRead(false)
        }.get(5, TimeUnit.SECONDS)
    }

    private fun resumeSocketReads(channel: Channel) {
        channel.eventLoop().submit {
            channel.config().setAutoRead(true)
            channel.read()
        }.get(5, TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    private fun pendingOutboundBytes(channel: Channel): Long =
        channel.eventLoop().submit<Long> {
            channel.unsafe().outboundBuffer()?.totalPendingWriteBytes() ?: 0L
        }.get(5, TimeUnit.SECONDS)

    private fun stalledPendingBytesMessage(
        peakPendingBytes: Long,
        writesIssued: Int,
        channel: Channel,
        socketBuffers: SocketBuffers
    ): String =
        "Expected stalled TCP receiver to keep sender parent pending outbound bytes <= " +
            "$MAX_ALLOWED_PENDING_BYTES, and to close/reset before exceeding that bound. " +
            "Instead peak pending outbound bytes reached $peakPendingBytes after issuing " +
            "$writesIssued writes across Yamux streams; channelActive=${channel.isActive}, " +
            "channelWritable=${channel.isWritable}, bytesBeforeWritable=${channel.bytesBeforeWritable()}, " +
            "bytesBeforeUnwritable=${channel.bytesBeforeUnwritable()}, " +
            "actualSoSndbuf=${socketBuffers.sendBufferBytes}, actualSoRcvbuf=${socketBuffers.receiveBufferBytes}."

    private fun assertYamuxBudgetFailureMessage(message: String, expectedPeer: String) {
        val pattern = Regex(
            "Yamux parent outbound buffer exceeded configured budget; " +
                "peer=${Regex.escape(expectedPeer)}, pendingBytes=\\d+, " +
                "attemptedFrameBytes=\\d+, projectedPendingBytes=\\d+, " +
                "budgetBytes=$PARENT_OUTBOUND_BUDGET_BYTES, " +
                "overBudgetDurationMillis=\\d+, " +
                "channel=\\[id: 0x[0-9a-f]+, L:/127\\.0\\.0\\.1:\\d+ - R:/127\\.0\\.0\\.1:\\d+\\]. " +
                "Closing stalled connection\\."
        )
        assertTrue(pattern.matches(message), "Unexpected full Yamux budget failure message: '$message'")
    }

    private fun awaitMatchingFailure(
        futures: List<CompletableFuture<Unit>>,
        predicate: (Throwable) -> Boolean
    ): Throwable {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            futures.mapNotNull { completedFailure(it) }.firstOrNull(predicate)?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("No write future completed with the expected failure. Failures=${futures.mapNotNull { completedFailure(it) }}")
    }

    private fun awaitFailure(future: CompletableFuture<Unit>): Throwable {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            completedFailure(future)?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("Expected write future to fail, but it did not complete exceptionally")
    }

    private fun awaitFutureDone(future: CompletableFuture<Unit>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(future.isDone, "Expected write future to complete after forced close")
    }

    private fun completedFailure(future: CompletableFuture<Unit>): Throwable? {
        if (!future.isDone) return null
        return try {
            future.join()
            null
        } catch (e: CompletionException) {
            e.cause ?: e
        }
    }

    private fun waitForCondition(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(predicate(), "Timed out waiting for $description")
    }

    private fun stopHostsPreservingFailure(primaryFailure: Throwable?, vararg hosts: Host) {
        var cleanupFailure: Throwable? = null
        hosts.forEach { host ->
            try {
                host.stop().get(5, TimeUnit.SECONDS)
            } catch (cause: Throwable) {
                if (cleanupFailure == null) {
                    cleanupFailure = cause
                } else {
                    cleanupFailure!!.addSuppressed(cause)
                }
            }
        }
        val failureToReport = cleanupFailure
        if (failureToReport != null) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(failureToReport)
            } else {
                throw failureToReport
            }
        }
    }
}

private data class ReceivedPayload(val bytes: Long, val crc: Long)
private data class SocketBuffers(val sendBufferBytes: Int, val receiveBufferBytes: Int)

private class BulkWriteProtocol(
    private val expectedBytes: Long,
    private val received: CompletableFuture<ReceivedPayload> = CompletableFuture(),
    private val responderStreams: AtomicInteger = AtomicInteger()
) : StrictProtocolBinding<BulkWriteController>(
    BULK_PROTOCOL_ID,
    BulkWriteProtocolHandler(BulkReceiveTracker(expectedBytes, received), responderStreams)
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
    private val receiveTracker: BulkReceiveTracker,
    private val responderStreams: AtomicInteger
) : ProtocolHandler<BulkWriteController>(Long.MAX_VALUE, Long.MAX_VALUE) {

    override fun onStartInitiator(stream: Stream): CompletableFuture<BulkWriteController> =
        CompletableFuture.completedFuture(BulkWriteController(stream))

    override fun onStartResponder(stream: Stream): CompletableFuture<BulkWriteController> {
        responderStreams.incrementAndGet()
        stream.pushHandler(CountingInboundHandler(receiveTracker))
        return CompletableFuture.completedFuture(BulkWriteController(stream))
    }
}

private class BulkWriteController(private val stream: Stream) {
    fun write(bytes: ByteArray) {
        stream.writeAndFlush(Unpooled.wrappedBuffer(bytes))
    }
}

private class CountingInboundHandler(
    private val receiveTracker: BulkReceiveTracker
) : SimpleChannelInboundHandler<ByteBuf>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        receiveTracker.record(msg.toByteArray())
    }
}

private class BulkReceiveTracker(
    private val expectedBytes: Long,
    private val received: CompletableFuture<ReceivedPayload>
) {
    private val crc = CRC32()
    private var bytes = 0L
    private var preambleOffset = 0

    @Synchronized
    fun record(data: ByteArray) {
        val payloadOffset = findPayloadOffset(data)
        if (payloadOffset == data.size) {
            return
        }

        val availablePayloadBytes = data.size - payloadOffset
        val remainingPayloadBytes = expectedBytes - bytes
        val bytesToRecord = minOf(availablePayloadBytes.toLong(), remainingPayloadBytes).toInt()
        bytes += bytesToRecord
        crc.update(data, payloadOffset, bytesToRecord)
        val extraPayloadBytes = availablePayloadBytes - bytesToRecord
        if (extraPayloadBytes > 0 && !received.isDone) {
            received.completeExceptionally(
                AssertionError(
                    "Received $extraPayloadBytes extra payload bytes after the expected " +
                        "$expectedBytes byte test payload"
                )
            )
            return
        }
        if (bytes == expectedBytes && !received.isDone) {
            received.complete(ReceivedPayload(bytes, crc.value))
        }
    }

    private fun findPayloadOffset(data: ByteArray): Int {
        var index = 0
        while (preambleOffset < PAYLOAD_PREAMBLE.size && index < data.size) {
            preambleOffset = nextPreambleOffset(data[index], preambleOffset)
            index++
        }
        return index
    }

    private fun nextPreambleOffset(byte: Byte, currentOffset: Int): Int {
        if (byte == PAYLOAD_PREAMBLE[currentOffset]) {
            return currentOffset + 1
        }
        return if (byte == PAYLOAD_PREAMBLE[0]) 1 else 0
    }
}
