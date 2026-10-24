package io.libp2p.transport

import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CompletedStreamWriteNotificationTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun rejectedStreamWriteRetainsItsFailureAfterLoopRecovery(yamux: Boolean) {
        runWriteNotificationProbe("rejected", yamux)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun failedParentWriteCompletesChildOnFullLoop(yamux: Boolean) {
        runWriteNotificationProbe("parent-failure", yamux)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun completedParentWriteCompletesChildOnFullLoop(yamux: Boolean) {
        runWriteNotificationProbe("parent", yamux)
    }
}

private fun runWriteNotificationProbe(mode: String, yamux: Boolean) {
    val output = Files.createTempFile("completed-stream-write-", ".log")
    val process = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").absolutePath,
        "-Xmx128m", "-Dio.netty.eventLoopThreads=1", "-Dio.netty.eventLoop.maxPendingTasks=16",
        "-cp", System.getProperty("java.class.path"),
        "io.libp2p.transport.CompletedStreamWriteProbe", mode, yamux.toString()
    ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
    try {
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "The isolated stream-write probe must finish")
        val text = Files.readString(output)
        print(text)
        assertEquals(0, process.exitValue(), text)
    } finally {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        Files.deleteIfExists(output)
    }
}

object CompletedStreamWriteProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val parentMode = args[0].startsWith("parent")
        val parentFailure = args[0] == "parent-failure"
        val writeFailure = IOException("The held parent write failed after listener attachment")
        val yamux = args[1].toBoolean()
        val protocol = "/completed-write-notification/1.0.0"
        val binding = object : StrictProtocolBinding<Stream>(
            protocol, object : P2PChannelHandler<Stream> {
                override fun initChannel(ch: P2PChannel): CompletableFuture<Stream> =
                    CompletableFuture.completedFuture(ch as Stream)
            }
        ) {}
        val createHost = {
            host {
                identity { random(KeyType.ED25519) }
                transports { add(::TcpTransport) }
                secureChannels { add(::NoiseXXSecureChannel) }
                muxers { +(if (yamux) StreamMuxerProtocol.getYamux() else StreamMuxerProtocol.Mplex) }
                protocols { +binding }
                network { listen("/ip4/127.0.0.1/tcp/0") }
            }
        }
        val server = createHost()
        val client = createHost()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(2048)
        val held = CompletableFuture<Triple<ChannelHandlerContext, Any, ChannelPromise>>()
        val holdNext = AtomicBoolean(parentMode)
        val forwarded = CompletableFuture<Unit>()
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val stream = client.newStream<Stream>(listOf(protocol), server.peerId, server.listenAddresses().first())
                .controller.get(5, TimeUnit.SECONDS)
            val parent = (stream.connection as P2PChannelOverNetty).nettyChannel
            val loop = parent.eventLoop()
            loop.submit {
                parent.pipeline().addFirst("hold-transport-write", object : ChannelOutboundHandlerAdapter() {
                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                        if (holdNext.compareAndSet(true, false)) held.complete(Triple(ctx, msg, promise))
                        else ctx.write(msg, promise)
                    }
                })
            }.get(5, TimeUnit.SECONDS)
            val payload = Unpooled.buffer(1).writeByte(7)
            var writing: CompletableFuture<Unit>? = null
            if (parentMode) {
                writing = stream.writeAndFlushWithFuture(payload)
                held.get(5, TimeUnit.SECONDS)
            }
            loop.execute {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "The test must release its event loop" }
                if (parentMode) {
                    try {
                        val (ctx, msg, promise) = held.get()
                        if (parentFailure) {
                            ReferenceCountUtil.release(msg)
                            promise.setFailure(writeFailure)
                        } else {
                            ctx.writeAndFlush(msg, promise)
                            assertTrue(promise.isSuccess, "The real transport write must complete on the full loop")
                        }
                        forwarded.complete(Unit)
                    } catch (failure: Throwable) {
                        forwarded.completeExceptionally(failure)
                    }
                }
            }
            check(entered.await(5, TimeUnit.SECONDS)) { "The event loop must enter its controlled wait" }
            repeat(2048) { loop.execute { drained.countDown() } }
            assertThrows(RejectedExecutionException::class.java) { loop.execute {} }
            assertFalse(loop.isShuttingDown)
            if (!parentMode) writing = stream.writeAndFlushWithFuture(payload)
            release.countDown()
            check(drained.await(5, TimeUnit.SECONDS)) { "The full event-loop queue must drain" }
            loop.submit {}.get(5, TimeUnit.SECONDS)
            if (parentMode) forwarded.get(5, TimeUnit.SECONDS)
            val result = checkNotNull(writing)
            assertTrue(
                result.isDone,
                if (parentMode) "A completed parent write must complete its child after the event loop recovers"
                else "A rejected stream write must report its failure after the event loop recovers"
            )
            if (parentMode && !parentFailure) result.get(1, TimeUnit.SECONDS) else {
                val failure = assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
                if (parentFailure) {
                    assertSame(writeFailure, failure.cause)
                    assertEquals("The held parent write failed after listener attachment", failure.cause!!.message)
                } else {
                    assertInstanceOf(RejectedExecutionException::class.java, failure.cause)
                }
            }
            assertEquals(0, payload.refCnt(), "The write must release its payload exactly once")
            stream.close().get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            if (held.isDone && !held.isCompletedExceptionally) {
                val (ctx, msg, promise) = held.get()
                if (!promise.isDone) ctx.writeAndFlush(msg, promise)
            }
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }
}
