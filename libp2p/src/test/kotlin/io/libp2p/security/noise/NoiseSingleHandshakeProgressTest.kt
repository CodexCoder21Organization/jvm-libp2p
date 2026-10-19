package io.libp2p.security.noise

import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.transport.implementation.ConnectionOverNetty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NoiseSingleHandshakeProgressTest {
    @Test
    fun `established ping progresses while one new Noise handshake computation is held`() {
        var child: Process? = null
        var output: CompletableFuture<String>? = null
        try {
            val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
            val builder = ProcessBuilder(
                java,
                "-Dio.netty.eventLoopThreads=2",
                "-cp",
                System.getProperty("java.class.path"),
                NoiseSingleHandshakeProgressHarness::class.java.name
            ).redirectErrorStream(true)
            builder.environment()["JAVA_TOOL_OPTIONS"] =
                "-Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:ActiveProcessorCount=2"

            val runningChild = builder.start()
            child = runningChild
            println("Started Noise single-handshake child JVM pid=${runningChild.pid()}")
            val outputReader = CompletableFuture.supplyAsync {
                runningChild.inputStream.bufferedReader().use { it.readText() }
            }
            output = outputReader

            val completed = runningChild.waitFor(20, TimeUnit.SECONDS)
            if (!completed && runningChild.isAlive) {
                runningChild.destroyForcibly()
                runningChild.waitFor(5, TimeUnit.SECONDS)
            }
            val text = outputReader.get(5, TimeUnit.SECONDS)
            print(text)
            assertTrue(
                completed,
                "The bounded child JVM (pid=${runningChild.pid()}) must finish within 20 seconds. Output:\n$text"
            )
            assertEquals(0, runningChild.exitValue(), text)
        } finally {
            child?.let { runningChild ->
                if (runningChild.isAlive) {
                    runningChild.destroyForcibly()
                    runningChild.waitFor(5, TimeUnit.SECONDS)
                }
            }
            output?.let { outputReader ->
                if (!outputReader.isDone) outputReader.get(5, TimeUnit.SECONDS)
            }
        }
    }
}

object NoiseSingleHandshakeProgressHarness {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val hosts = mutableListOf<Host>()
        val streams = mutableListOf<Stream>()
        val captureRawConnection = AtomicBoolean(false)
        val observerArmed = AtomicBoolean(false)
        val observerCalls = AtomicInteger()
        val rawServerConnection = CompletableFuture<ConnectionOverNetty>()
        val establishedController = AtomicReference<PingController?>()
        val progressDuringComputation = CompletableFuture<ProgressDuringComputation>()
        var primaryFailure: Throwable? = null

        try {
            val server = progressHost(
                computationObserver = { computation ->
                    if (observerArmed.get()) {
                        val callNumber = observerCalls.incrementAndGet()
                        if (callNumber == 1) {
                            val controller = establishedController.get()
                            var pingCompleted = false
                            var failureDetails: String? = null
                            try {
                                check(controller != null) {
                                    "The established Ping controller must be ready before the raw Noise frame is released"
                                }
                                controller.ping().get(1, TimeUnit.SECONDS)
                                pingCompleted = true
                            } catch (_: TimeoutException) {
                                failureDetails = "The established Ping did not complete within the one-second observation window"
                            } catch (failure: Throwable) {
                                failureDetails = failure.stackTraceToString()
                            }
                            progressDuringComputation.complete(
                                ProgressDuringComputation(computation, pingCompleted, failureDetails)
                            )
                        }
                    }
                },
                onBeforeSecureConnection = { connection ->
                    if (captureRawConnection.get()) rawServerConnection.complete(connection)
                }
            ).also(hosts::add)
            val stableClient = progressHost().also(hosts::add)

            CompletableFuture.allOf(*hosts.map { it.start() }.toTypedArray()).get(5, TimeUnit.SECONDS)

            val stablePromise = stableClient.newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"),
                server.peerId,
                *server.listenAddresses().toTypedArray()
            )
            val stableStream = stablePromise.stream.get(5, TimeUnit.SECONDS)
            streams += stableStream
            val stablePing = stablePromise.controller.get(5, TimeUnit.SECONDS)
            establishedController.set(stablePing)
            stablePing.ping().get(5, TimeUnit.SECONDS)

            val stableServerConnection = server.network.connections.singleOrNull()
                ?: error("The verified Ping must leave exactly one server-side connection, but found ${server.network.connections.size}")
            val stableServerNettyConnection = stableServerConnection as? ConnectionOverNetty
                ?: error("The server-side Ping connection must be backed by ConnectionOverNetty")
            val stableClientConnection = stableClient.network.connections.singleOrNull()
                ?: error("The verified Ping must leave exactly one client-side connection, but found ${stableClient.network.connections.size}")
            val stableClientNettyConnection = stableClientConnection as? ConnectionOverNetty
                ?: error("The client-side Ping connection must be backed by ConnectionOverNetty")

            val stableServerLoop = stableServerNettyConnection.nettyChannel.eventLoop()
            val stableClientLoop = stableClientNettyConnection.nettyChannel.eventLoop()
            check(stableServerLoop !== stableClientLoop) {
                "The two-worker fixture must place the established Ping endpoints on separate event loops"
            }

            val tcpPort = requireNotNull(
                server.listenAddresses().single().getFirstComponent(io.libp2p.core.multiformats.Protocol.TCP)?.stringValue
            ) { "The server must expose one loopback TCP port: ${server.listenAddresses()}" }.toInt()

            captureRawConnection.set(true)
            val rawHandshake = Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", tcpPort), 5_000)
                socket.soTimeout = 5_000

                val socketInput = socket.getInputStream()
                val socketOutput = socket.getOutputStream()
                writeNoiseMultistreamMessage(socketOutput, "/multistream/1.0.0")
                check(readNoiseMultistreamMessage(socketInput) == "/multistream/1.0.0") {
                    "The server must acknowledge the real multistream version selection"
                }
                writeNoiseMultistreamMessage(socketOutput, NoiseXXSecureChannel.announce)
                check(readNoiseMultistreamMessage(socketInput) == NoiseXXSecureChannel.announce) {
                    "The server must acknowledge selection of the real Noise protocol"
                }

                val rawConnection = rawServerConnection.get(5, TimeUnit.SECONDS)
                val rawChannel = rawConnection.nettyChannel
                val rawChannelId = rawChannel.id().asLongText()
                val rawEventLoop = rawChannel.eventLoop()
                val rawSharesServerEndpointLoop = rawEventLoop === stableServerLoop
                val rawSharesClientEndpointLoop = rawEventLoop === stableClientLoop
                val rawSharesEstablishedEndpointLoop = rawSharesServerEndpointLoop || rawSharesClientEndpointLoop
                check(rawSharesEstablishedEndpointLoop) {
                    "With two global NIO workers, the raw server channel must share one of the established Ping endpoint loops"
                }

                // These public Netty event-loop submissions drain the registration and
                // multistream selection work before the first Noise frame reaches the
                // handshake admission decision.
                stableServerNettyConnection.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
                stableClientNettyConnection.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
                rawChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)

                observerArmed.set(true)
                val initiatorEphemeral = ByteArray(32).also { it[0] = 9 }
                writeNoiseFrame(socketOutput, initiatorEphemeral)

                val result = progressDuringComputation.get(5, TimeUnit.SECONDS)
                val responderMessage = readNoiseFrame(socketInput)
                check(responderMessage.isNotEmpty()) {
                    "The real responder must send Noise message 2 after receiving the valid initiator message 1"
                }

                println(
                    "Raw Noise progress: observedChannel=${result.computation.channelId}, rawServerChannel=$rawChannelId, " +
                        "rawSharesServerEndpointLoop=$rawSharesServerEndpointLoop, " +
                        "rawSharesClientEndpointLoop=$rawSharesClientEndpointLoop, " +
                        "onEventLoop=${result.computation.onEventLoop}, " +
                        "pendingHandshakesOnLoop=${result.computation.pendingHandshakesOnLoop}, " +
                        "observerCalls=${observerCalls.get()}, " +
                        "establishedPingCompletedBeforeObserverReturn=${result.pingCompleted}, " +
                        "responderMessage2Bytes=${responderMessage.size}, " +
                        "observerFailure=${result.failureDetails ?: "none"}"
                )
                RawHandshakeResult(
                    progress = result,
                    channelId = rawChannelId,
                    sharesEstablishedEndpointLoop = rawSharesEstablishedEndpointLoop
                )
            }

            stablePing.ping().get(5, TimeUnit.SECONDS)

            check(rawHandshake.progress.computation.channelId == rawHandshake.channelId) {
                "The observed Noise computation must belong to the newly captured raw server channel"
            }
            check(rawHandshake.sharesEstablishedEndpointLoop) {
                "The observed raw handshake must share an established Ping endpoint event loop"
            }
            check(observerCalls.get() == 1) {
                "Exactly one Noise computation must enter the observer before the raw initiator closes"
            }
            check(rawHandshake.progress.computation.pendingHandshakesOnLoop == 1) {
                "The observed handshake must be the only pending handshake on its event loop"
            }
            check(rawHandshake.progress.pingCompleted) {
                "The established real Ping must complete before the one new Noise computation observer returns"
            }
            check(rawHandshake.progress.failureDetails == null) {
                "The established Ping failed while the new Noise computation was observed: ${rawHandshake.progress.failureDetails}"
            }
            println("The established Ping remained usable after the raw handshake connection closed.")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            streams.asReversed().forEach { stream ->
                try {
                    stream.close().get(5, TimeUnit.SECONDS)
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            val stops = hosts.asReversed().mapNotNull { node ->
                try {
                    node.stop()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                    null
                }
            }
            if (stops.isNotEmpty()) {
                try {
                    CompletableFuture.allOf(*stops.toTypedArray()).get(5, TimeUnit.SECONDS)
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            if (cleanupFailures.isNotEmpty()) {
                val mainFailure = primaryFailure ?: cleanupFailures.removeAt(0)
                cleanupFailures.forEach(mainFailure::addSuppressed)
                if (primaryFailure == null) throw mainFailure
            }
        }
    }

    private data class ProgressDuringComputation(
        val computation: NoiseHandshakeComputation,
        val pingCompleted: Boolean,
        val failureDetails: String?
    )

    private data class RawHandshakeResult(
        val progress: ProgressDuringComputation,
        val channelId: String,
        val sharesEstablishedEndpointLoop: Boolean
    )
}

private fun progressHost(
    computationObserver: ((NoiseHandshakeComputation) -> Unit)? = null,
    onBeforeSecureConnection: ((ConnectionOverNetty) -> Unit)? = null
): Host = host {
    identity { random(KeyType.ED25519) }
    network { listen("/ip4/127.0.0.1/tcp/0") }
    protocols { +Ping() }
    if (computationObserver != null) {
        secureChannels {
            add { key, _ -> NoiseXXSecureChannel(key, computationObserver) }
        }
    }
    if (onBeforeSecureConnection != null) {
        debug {
            this.beforeSecureHandler.addHandler { channel ->
                val connection = channel as? ConnectionOverNetty
                    ?: error("The pre-secure server channel must be backed by ConnectionOverNetty")
                onBeforeSecureConnection(connection)
            }
        }
    }
}

private fun writeNoiseMultistreamMessage(output: OutputStream, message: String) {
    val bytes = "$message\n".toByteArray(Charsets.UTF_8)
    require(bytes.size < 128) { "Test multistream message is too long for its one-byte varint: $message" }
    output.write(bytes.size)
    output.write(bytes)
    output.flush()
}

private fun readNoiseMultistreamMessage(input: InputStream): String {
    val length = input.read()
    check(length in 1 until 128) { "Expected a one-byte multistream frame length, but received $length" }
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < bytes.size) {
        val read = input.read(bytes, offset, bytes.size - offset)
        check(read >= 0) { "Socket closed after $offset of ${bytes.size} multistream frame bytes" }
        offset += read
    }
    return bytes.toString(Charsets.UTF_8).removeSuffix("\n")
}

private fun writeNoiseFrame(output: OutputStream, payload: ByteArray) {
    require(payload.size in 1..0xFFFF) { "Noise frame must fit the two-byte unsigned length prefix" }
    output.write((payload.size ushr 8) and 0xFF)
    output.write(payload.size and 0xFF)
    output.write(payload)
    output.flush()
}

private fun readNoiseFrame(input: InputStream): ByteArray {
    val high = input.read()
    val low = input.read()
    check(high >= 0 && low >= 0) { "Socket closed before the responder's Noise message 2 length prefix" }
    val length = (high shl 8) or low
    check(length > 0) { "The responder's Noise message 2 must not be empty" }
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < bytes.size) {
        val read = input.read(bytes, offset, bytes.size - offset)
        check(read >= 0) { "Socket closed after $offset of $length responder Noise message 2 bytes" }
        offset += read
    }
    return bytes
}
