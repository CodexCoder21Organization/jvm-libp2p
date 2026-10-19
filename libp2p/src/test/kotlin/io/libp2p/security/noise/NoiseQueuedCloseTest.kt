package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.security.SecureChannel
import io.libp2p.protocol.Ping
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NoiseQueuedCloseTest {
    @Test
    fun `closing an admitted responder handshake prevents its queued computation`() {
        var child: Process? = null
        var outputFile: Path? = null
        var primaryFailure: Throwable? = null
        try {
            val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
            val builder = ProcessBuilder(
                java,
                "-Dio.netty.eventLoopThreads=2",
                "-cp",
                System.getProperty("java.class.path"),
                NoiseQueuedCloseHarness::class.java.name
            ).redirectErrorStream(true)
            builder.environment()["JAVA_TOOL_OPTIONS"] =
                "-Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:ActiveProcessorCount=2"
            val childOutputFile = Files.createTempFile("noise-queued-close-", ".log")
            outputFile = childOutputFile
            builder.redirectOutput(childOutputFile.toFile())

            val runningChild = builder.start()
            child = runningChild
            println("Started Noise queued-close child JVM pid=${runningChild.pid()}")

            val completed = runningChild.waitFor(20, TimeUnit.SECONDS)
            var reapedAfterForcedTermination = true
            if (!completed && runningChild.isAlive) {
                runningChild.destroyForcibly()
                reapedAfterForcedTermination = runningChild.waitFor(5, TimeUnit.SECONDS)
            }
            val text = Files.readString(childOutputFile)

            assertTrue(
                reapedAfterForcedTermination,
                "Noise queued-close child JVM (pid=${runningChild.pid()}) was not reaped within five seconds after forced termination. Output:\n$text"
            )
            assertTrue(
                completed,
                "Noise queued-close child JVM (pid=${runningChild.pid()}) did not finish within 20 seconds. Output:\n$text"
            )
            assertEquals(0, runningChild.exitValue(), text)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            child?.let { runningChild ->
                try {
                    if (runningChild.isAlive) {
                        runningChild.destroyForcibly()
                        check(runningChild.waitFor(5, TimeUnit.SECONDS)) {
                            "Noise queued-close child JVM (pid=${runningChild.pid()}) was not reaped within five seconds"
                        }
                    }
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            outputFile?.let { childOutputFile ->
                try {
                    print(Files.readString(childOutputFile))
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                } finally {
                    try {
                        Files.deleteIfExists(childOutputFile)
                    } catch (failure: Throwable) {
                        cleanupFailures += failure
                    }
                }
            }
            if (cleanupFailures.isNotEmpty()) {
                val failureToReport = primaryFailure ?: cleanupFailures.removeAt(0)
                cleanupFailures.forEach(failureToReport::addSuppressed)
                if (primaryFailure == null) throw failureToReport
            }
        }
    }
}

object NoiseQueuedCloseHarness {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val hosts = mutableListOf<Host>()
        val sockets = mutableListOf<Socket>()
        val capturedConnections = ArrayBlockingQueue<ConnectionOverNetty>(QUEUED_CLOSE_SOCKET_COUNT)
        val secureFutures = ConcurrentHashMap<String, CompletableFuture<SecureChannel.Session>>()
        val slotsByChannelId = ConcurrentHashMap<String, NoiseQueuedCloseSlot>()
        val unmatchedObserverChannelIds = ConcurrentLinkedQueue<String>()
        val releaseBlockers = CountDownLatch(1)
        val releaseSentinels = CountDownLatch(1)
        var primaryFailure: Throwable? = null

        try {
            val relay = newNoiseQueuedCloseHost(
                capturedConnections,
                secureFutures,
                slotsByChannelId,
                unmatchedObserverChannelIds
            ).also(hosts::add)
            relay.start().get(5, TimeUnit.SECONDS)

            val tcpPort = requireNotNull(
                relay.listenAddresses().single().getFirstComponent(Protocol.TCP)?.stringValue
            ) { "The Noise queued-close relay did not expose one TCP address: ${relay.listenAddresses()}" }.toInt()
            val address = InetSocketAddress("127.0.0.1", tcpPort)
            val labels = listOf("guard-1", "guard-2", "guard-3", "guard-4", "A", "B", "C", "D", "E")
            val selected = labels.map { label ->
                openNoiseQueuedCloseSocket(
                    label,
                    address,
                    sockets,
                    capturedConnections,
                    secureFutures,
                    slotsByChannelId,
                    releaseBlockers,
                    releaseSentinels
                )
            }
            check(selected.size == QUEUED_CLOSE_SOCKET_COUNT) {
                "The fixture must keep exactly nine selected raw sockets open, but selected ${selected.size}"
            }
            val expiredBeforeFirstFrame = selected.filter { it.secureFuture.isDone || !it.connection.nettyChannel.isActive }
            check(expiredBeforeFirstFrame.isEmpty()) {
                "Every selected responder must remain pending until its first Noise frame; expired before any frame: " +
                    expiredBeforeFirstFrame.joinToString { it.summary() }
            }

            // These transparent markers prove the first-frame bytes reached the Noise handler before later steps.
            for (state in selected.filter { it.firstFrameMarker != null }) {
                val marker = requireNotNull(state.firstFrameMarker)
                state.connection.nettyChannel.eventLoop().submit {
                    state.connection.nettyChannel.pipeline().addFirst(
                        "noise-queued-close-${state.label}-first-frame-bytes",
                        marker
                    )
                }.get(5, TimeUnit.SECONDS)
                noiseQueuedCloseEventLoopBarrier(state.connection, state.label)
            }

            val guardSockets = selected.take(4)
            val a = selected[4]
            val b = selected[5]
            val c = selected[6]
            val d = selected[7]
            val e = selected[8]
            check(guardSockets.all { it.slot.observerCount.get() == 0 }) {
                "No first-frame computation may start on any of the four guard sockets before its marker is sent: " +
                    guardSockets.joinToString { it.slot.summary() }
            }

            writeNoiseQueuedCloseFirstFrame(a.socket.getOutputStream())
            writeNoiseQueuedCloseFirstFrame(b.socket.getOutputStream())
            val aObservation = awaitNoiseQueuedCloseObserver(a.slot)
            val bObservation = awaitNoiseQueuedCloseObserver(b.slot)
            println(
                "Noise queued-close blockers entered: A=${aObservation.summary()}, B=${bObservation.summary()}"
            )
            check(!aObservation.computation.onEventLoop && !bObservation.computation.onEventLoop) {
                "A and B must enter the public computation observer on workers: ${aObservation.summary()}, ${bObservation.summary()}"
            }
            check(aObservation.thread !== bObservation.thread) {
                "A and B must hold two distinct actual worker threads: ${aObservation.summary()}, ${bObservation.summary()}"
            }
            check(aObservation.computation.pendingHandshakesOnLoop > 1 && bObservation.computation.pendingHandshakesOnLoop > 1) {
                "Both blocker observations must see more than one pending handshake on their loops: " +
                    "A=${aObservation.summary()}, B=${bObservation.summary()}"
            }
            check(!a.slot.callbackReturned.isDone && !b.slot.callbackReturned.isDone && releaseBlockers.count == 1L) {
                "Both worker callbacks must remain held before C is admitted: A=${a.slot.summary()}, B=${b.slot.summary()}"
            }

            writeNoiseQueuedCloseFirstFrame(c.socket.getOutputStream())
            val cDeliveredBytes = awaitNoiseQueuedCloseFrame(c)
            check(cDeliveredBytes == NOISE_FIRST_FRAME_BYTES) {
                "C's complete $NOISE_FIRST_FRAME_BYTES-byte first frame must reach Noise before close; " +
                    "observed $cDeliveredBytes bytes for ${c.summary()}"
            }
            noiseQueuedCloseEventLoopBarrier(c.connection, c.label)
            c.connection.close().get(5, TimeUnit.SECONDS)
            c.connection.closeFuture().get(5, TimeUnit.SECONDS)
            noiseQueuedCloseEventLoopBarrier(c.connection, c.label)
            val cSecureFailure = awaitNoiseQueuedCloseExceptionalFuture(c)
            check(relay.network.connections.none { it === c.connection }) {
                "The closed C connection must not be published in the relay's network connections; " +
                    "secureFailure=$cSecureFailure; C=${c.summary()}"
            }
            println("Closed C after its first frame was forwarded; its public secure-channel future failed: $cSecureFailure")

            writeNoiseQueuedCloseFirstFrame(d.socket.getOutputStream())
            writeNoiseQueuedCloseFirstFrame(e.socket.getOutputStream())
            val dDeliveredBytes = awaitNoiseQueuedCloseFrame(d)
            val eDeliveredBytes = awaitNoiseQueuedCloseFrame(e)
            check(dDeliveredBytes == NOISE_FIRST_FRAME_BYTES && eDeliveredBytes == NOISE_FIRST_FRAME_BYTES) {
                "D and E must both deliver complete first frames before blocker release: " +
                    "D=$dDeliveredBytes (${d.summary()}), E=$eDeliveredBytes (${e.summary()})"
            }
            noiseQueuedCloseEventLoopBarrier(d.connection, d.label)
            noiseQueuedCloseEventLoopBarrier(e.connection, e.label)

            releaseBlockers.countDown()
            val dObservation = awaitNoiseQueuedCloseObserver(d.slot)
            val eObservation = awaitNoiseQueuedCloseObserver(e.slot)
            println(
                "Noise queued-close sentinels entered: D=${dObservation.summary()}, E=${eObservation.summary()}"
            )
            check(!dObservation.computation.onEventLoop && !eObservation.computation.onEventLoop) {
                "D and E must enter the public computation observer on workers: ${dObservation.summary()}, ${eObservation.summary()}"
            }
            check(dObservation.thread !== eObservation.thread) {
                "D and E must hold two distinct actual worker threads: ${dObservation.summary()}, ${eObservation.summary()}"
            }
            check(c.slot.observerCount.get() == 0) {
                "C's closed queued computation must not enter the observer before later D and E sentinels: " +
                    "C=${c.slot.summary()}, D=${d.slot.summary()}, E=${e.slot.summary()}"
            }

            releaseSentinels.countDown()
            for (state in listOf(a, b, d, e)) {
                awaitNoiseQueuedCloseCallbackReturn(state.slot)
            }
            for (state in listOf(a, b, d, e)) {
                val message2 = readNoiseQueuedCloseFrame(state.socket.getInputStream())
                check(message2.size == NOISE_RESPONDER_MESSAGE_TWO_BYTES) {
                    "${state.label} must receive the real $NOISE_RESPONDER_MESSAGE_TWO_BYTES-byte responder Noise message 2, " +
                        "but received ${message2.size} bytes; ${state.summary()}"
                }
            }

            for (state in listOf(a, b, d, e)) {
                check(state.slot.observerCount.get() == 1) {
                    "${state.label} must have exactly one public Noise computation observation: ${state.slot.summary()}"
                }
            }
            check(c.slot.observerCount.get() == 0) {
                "C must have no public Noise computation observation after both sentinels entered: ${c.slot.summary()}"
            }
            check(guardSockets.all { it.slot.observerCount.get() == 0 }) {
                "The four no-frame guard sockets must have no computation observations: " +
                    guardSockets.joinToString { it.slot.summary() }
            }
            check(unmatchedObserverChannelIds.isEmpty()) {
                "Every public Noise observation must match one of the nine selected connection IDs: $unmatchedObserverChannelIds"
            }
            check(c.secureFuture.isCompletedExceptionally) {
                "C's public secure-channel future must remain failed after later callbacks: ${c.summary()}"
            }
            check(relay.network.connections.none { it === c.connection }) {
                "The closed C connection must remain absent from published network connections: ${c.summary()}"
            }
            println(
                "Noise queued-close verified: A/B/D/E each observed once and returned a $NOISE_RESPONDER_MESSAGE_TWO_BYTES-byte " +
                    "responder frame; C observed ${c.slot.observerCount.get()} times and remains unpublished."
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseBlockers.countDown()
            releaseSentinels.countDown()
            val cleanupFailures = mutableListOf<Throwable>()
            sockets.asReversed().forEach { socket ->
                try {
                    socket.close()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            hosts.asReversed().forEach { node ->
                try {
                    node.stop().get(5, TimeUnit.SECONDS)
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            if (cleanupFailures.isNotEmpty()) {
                val failureToReport = primaryFailure ?: cleanupFailures.removeAt(0)
                cleanupFailures.forEach(failureToReport::addSuppressed)
                if (primaryFailure == null) throw failureToReport
            }
        }
    }
}

private class RecordingNoiseQueuedCloseSecureChannel(
    private val delegate: NoiseXXSecureChannel,
    private val futuresByChannelId: ConcurrentHashMap<String, CompletableFuture<SecureChannel.Session>>
) : SecureChannel {
    override val protocolDescriptor = delegate.protocolDescriptor

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
        val channelId = (ch as? P2PChannelOverNetty)?.nettyChannel?.id()?.asLongText()
            ?: error("The selected Noise channel must be backed by P2PChannelOverNetty: $ch")
        val future = delegate.initChannel(ch, selectedProtocol)
        check(futuresByChannelId.putIfAbsent(channelId, future) == null) {
            "The secure-channel future must be recorded once for channel $channelId"
        }
        return future
    }
}

private fun newNoiseQueuedCloseHost(
    capturedConnections: ArrayBlockingQueue<ConnectionOverNetty>,
    secureFutures: ConcurrentHashMap<String, CompletableFuture<SecureChannel.Session>>,
    slotsByChannelId: ConcurrentHashMap<String, NoiseQueuedCloseSlot>,
    unmatchedObserverChannelIds: ConcurrentLinkedQueue<String>
): Host = host {
    identity { random(KeyType.ED25519) }
    network { listen("/ip4/127.0.0.1/tcp/0") }
    protocols { +Ping() }
    secureChannels {
        add { key, _ ->
            val noise = NoiseXXSecureChannel(key) { computation ->
                val slot = slotsByChannelId[computation.channelId]
                if (slot == null) {
                    unmatchedObserverChannelIds.add(computation.channelId)
                } else {
                    slot.observe(computation)
                }
            }
            RecordingNoiseQueuedCloseSecureChannel(noise, secureFutures)
        }
    }
    debug {
        beforeSecureHandler.addHandler { channel ->
            val connection = channel as? ConnectionOverNetty
                ?: error("The queued-close raw server channel must be a ConnectionOverNetty: $channel")
            check(capturedConnections.offer(connection)) {
                "The bounded queue of ${QUEUED_CLOSE_SOCKET_COUNT} captured connections must not overflow"
            }
        }
    }
}

private fun openNoiseQueuedCloseSocket(
    label: String,
    address: InetSocketAddress,
    sockets: MutableList<Socket>,
    capturedConnections: ArrayBlockingQueue<ConnectionOverNetty>,
    secureFutures: ConcurrentHashMap<String, CompletableFuture<SecureChannel.Session>>,
    slotsByChannelId: ConcurrentHashMap<String, NoiseQueuedCloseSlot>,
    releaseBlockers: CountDownLatch,
    releaseSentinels: CountDownLatch
): NoiseQueuedCloseSocket {
    val socket = Socket()
    sockets += socket
    socket.connect(address, FIVE_SECONDS_MILLIS)
    socket.soTimeout = FIVE_SECONDS_MILLIS

    val input = socket.getInputStream()
    val output = socket.getOutputStream()
    writeNoiseQueuedCloseMultistreamMessage(output, "/multistream/1.0.0")
    check(readNoiseQueuedCloseMultistreamMessage(input) == "/multistream/1.0.0") {
        "$label must receive the real multistream version acknowledgment"
    }
    writeNoiseQueuedCloseMultistreamMessage(output, NoiseXXSecureChannel.announce)
    check(readNoiseQueuedCloseMultistreamMessage(input) == NoiseXXSecureChannel.announce) {
        "$label must receive the real Noise protocol selection acknowledgment"
    }

    val connection = capturedConnections.poll(5, TimeUnit.SECONDS)
        ?: error("Timed out waiting for the real server connection for $label after both protocol acknowledgments")
    val channelId = connection.nettyChannel.id().asLongText()
    noiseQueuedCloseEventLoopBarrier(connection, label)
    val secureFuture = secureFutures[channelId]
        ?: error("The public secure-channel future for $label channel $channelId was not recorded after selection")
    check(!secureFuture.isDone) {
        "$label secure-channel future completed before any first Noise frame; channelId=$channelId"
    }

    val gate = when (label) {
        "A", "B" -> releaseBlockers
        "D", "E" -> releaseSentinels
        else -> null
    }
    val slot = NoiseQueuedCloseSlot(label, channelId, gate)
    check(slotsByChannelId.putIfAbsent(channelId, slot) == null) {
        "Every selected raw socket must have a distinct server channel ID; duplicate $channelId for $label"
    }
    val marker = if (label in setOf("C", "D", "E")) NoiseQueuedCloseFirstFrameMarker() else null
    return NoiseQueuedCloseSocket(label, socket, connection, secureFuture, slot, marker)
}

private class NoiseQueuedCloseSlot(
    val label: String,
    val channelId: String,
    val releaseGate: CountDownLatch?
) {
    val observerCount = AtomicInteger()
    val callbacks = ConcurrentLinkedQueue<NoiseQueuedCloseObservation>()
    val observerEntered = CompletableFuture<NoiseQueuedCloseObservation>()
    val callbackReturned = CompletableFuture<Unit>()
}

private data class NoiseQueuedCloseObservation(
    val computation: NoiseHandshakeComputation,
    val thread: Thread
)

private data class NoiseQueuedCloseSocket(
    val label: String,
    val socket: Socket,
    val connection: ConnectionOverNetty,
    val secureFuture: CompletableFuture<SecureChannel.Session>,
    val slot: NoiseQueuedCloseSlot,
    val firstFrameMarker: NoiseQueuedCloseFirstFrameMarker?
)

private fun NoiseQueuedCloseSlot.observe(computation: NoiseHandshakeComputation) {
    val callback = NoiseQueuedCloseObservation(computation, Thread.currentThread())
    callbacks.add(callback)
    if (observerCount.incrementAndGet() == 1) observerEntered.complete(callback)

    try {
        val released = releaseGate?.await(5, TimeUnit.SECONDS) ?: true
        check(released) {
            "The controlled Noise observer gate for ${summary()} was not released within five seconds"
        }
        callbackReturned.complete(Unit)
    } catch (failure: Throwable) {
        callbackReturned.completeExceptionally(failure)
        throw failure
    }
}

private fun NoiseQueuedCloseSlot.summary(): String =
    "$label channelId=$channelId observerCount=${observerCount.get()} callbacks=" +
        callbacks.joinToString(prefix = "[", postfix = "]") { it.summary() }

private fun NoiseQueuedCloseObservation.summary(): String =
    "channelId=${computation.channelId}, thread=${thread.name}@${System.identityHashCode(thread)}, " +
        "onEventLoop=${computation.onEventLoop}, pending=${computation.pendingHandshakesOnLoop}"

private fun NoiseQueuedCloseSocket.summary(): String = "${slot.summary()}, active=${connection.nettyChannel.isActive}"

private class NoiseQueuedCloseFirstFrameMarker : ChannelInboundHandlerAdapter() {
    val completeFrameBytes = CompletableFuture<Int>()
    private var forwardedByteCount = 0

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val readableBytesBeforeForwarding = (msg as? ByteBuf)?.readableBytes() ?: 0
        try {
            ctx.fireChannelRead(msg)
        } catch (failure: Throwable) {
            completeFrameBytes.completeExceptionally(failure)
            throw failure
        }
        forwardedByteCount += readableBytesBeforeForwarding
        if (forwardedByteCount >= NOISE_FIRST_FRAME_BYTES && !completeFrameBytes.isDone) {
            completeFrameBytes.complete(forwardedByteCount)
        }
    }
}

private fun awaitNoiseQueuedCloseObserver(slot: NoiseQueuedCloseSlot): NoiseQueuedCloseObservation =
    try {
        slot.observerEntered.get(5, TimeUnit.SECONDS)
    } catch (failure: Throwable) {
        throw IllegalStateException("Timed out waiting for the ${slot.label} Noise observer entry: ${slot.summary()}", failure)
    }

private fun awaitNoiseQueuedCloseCallbackReturn(slot: NoiseQueuedCloseSlot) {
    try {
        slot.callbackReturned.get(5, TimeUnit.SECONDS)
    } catch (failure: Throwable) {
        throw IllegalStateException("Timed out waiting for the ${slot.label} Noise observer return: ${slot.summary()}", failure)
    }
}

private fun awaitNoiseQueuedCloseFrame(state: NoiseQueuedCloseSocket): Int {
    val marker = requireNotNull(state.firstFrameMarker) { "${state.label} has no first-frame marker: ${state.summary()}" }
    return try {
        marker.completeFrameBytes.get(5, TimeUnit.SECONDS)
    } catch (failure: Throwable) {
        throw IllegalStateException("Timed out waiting for ${state.label}'s complete forwarded first frame: ${state.summary()}", failure)
    }
}

private fun awaitNoiseQueuedCloseExceptionalFuture(state: NoiseQueuedCloseSocket): Throwable {
    try {
        state.secureFuture.get(5, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) {
        return failure.cause ?: failure
    }
    error("${state.label}'s public secure-channel future completed successfully instead of failing on close: ${state.summary()}")
}

private fun noiseQueuedCloseEventLoopBarrier(connection: ConnectionOverNetty, label: String) {
    try {
        connection.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
    } catch (failure: Throwable) {
        throw IllegalStateException("The public event-loop barrier for $label did not complete: channel=${connection.nettyChannel}", failure)
    }
}

private fun writeNoiseQueuedCloseMultistreamMessage(output: OutputStream, message: String) {
    val bytes = "$message\n".toByteArray(Charsets.UTF_8)
    require(bytes.size < 128) { "Queued-close test multistream message is too long for one-byte varint: $message" }
    output.write(bytes.size)
    output.write(bytes)
    output.flush()
}

private fun readNoiseQueuedCloseMultistreamMessage(input: InputStream): String {
    val length = input.read()
    check(length in 1 until 128) { "Expected one-byte multistream length, received $length" }
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < bytes.size) {
        val read = input.read(bytes, offset, bytes.size - offset)
        check(read >= 0) { "Socket closed after $offset of ${bytes.size} multistream bytes" }
        offset += read
    }
    return bytes.toString(Charsets.UTF_8).removeSuffix("\n")
}

private fun writeNoiseQueuedCloseFirstFrame(output: OutputStream) {
    val ephemeralPublicKey = ByteArray(32).also { it[0] = 9 }
    output.write((ephemeralPublicKey.size ushr 8) and 0xFF)
    output.write(ephemeralPublicKey.size and 0xFF)
    output.write(ephemeralPublicKey)
    output.flush()
}

private fun readNoiseQueuedCloseFrame(input: InputStream): ByteArray {
    val high = input.read()
    val low = input.read()
    check(high >= 0 && low >= 0) { "Socket closed before the responder Noise frame length prefix" }
    val length = (high shl 8) or low
    check(length > 0) { "Responder Noise frame must not be empty" }
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < bytes.size) {
        val read = input.read(bytes, offset, bytes.size - offset)
        check(read >= 0) { "Socket closed after $offset of $length responder Noise frame bytes" }
        offset += read
    }
    return bytes
}

private const val QUEUED_CLOSE_SOCKET_COUNT = 9
private const val FIVE_SECONDS_MILLIS = 5_000
private const val NOISE_FIRST_FRAME_BYTES = 34
private const val NOISE_RESPONDER_MESSAGE_TWO_BYTES = 204
