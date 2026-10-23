package io.libp2p.security.noise

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.etc.CONNECTION
import io.libp2p.tools.TestChannel
import io.libp2p.multistream.MultistreamProtocolDebugV1
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.util.ReferenceCountUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class NoiseTimerOrderControlTest {
    @RepeatedTest(10)
    fun testResponderTimerPrecedesInitiatorTimer() {
        val (clientKey, clientPublic) = generateKeyPair(KeyType.ED25519)
        val (serverKey, serverPublic) = generateKeyPair(KeyType.ED25519)
        val client = TestChannel("timer-order-client", true, remotePeerId = PeerId.fromPubKey(serverPublic))
        val server = TestChannel("timer-order-server", false, remotePeerId = PeerId.fromPubKey(clientPublic))
        client.freezeTime()
        server.freezeTime()
        var omittedMessage = false
        server.pipeline().addLast(object : ChannelOutboundHandlerAdapter() {
            override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                if (msg is ByteBuf && msg.readableBytes() >= 32) {
                    omittedMessage = true
                    ReferenceCountUtil.release(msg)
                    promise.setSuccess()
                } else {
                    ctx.write(msg, promise)
                }
            }
        })
        try {
            val events = mutableListOf<String>()
            val multistream = MultistreamProtocolDebugV1()
            val clientReady = multistream.createMultistream(listOf(NoiseXXSecureChannel(clientKey)))
                .initChannel(client.attr(CONNECTION).get())
            val serverReady = multistream.createMultistream(listOf(NoiseXXSecureChannel(serverKey)))
                .initChannel(server.attr(CONNECTION).get())
            serverReady.whenComplete { _, cause -> events += "responder:${cause?.javaClass?.simpleName}:${Thread.currentThread().name}" }
            clientReady.whenComplete { _, cause -> events += "initiator:${cause?.javaClass?.simpleName}:${Thread.currentThread().name}" }
            client.pipeline().fireChannelActive()
            server.pipeline().fireChannelActive()
            fun transfer(from: TestChannel, to: TestChannel) {
                from.runPendingTasks()
                generateSequence { from.readOutbound<ByteBuf>() }.forEach { bytes ->
                    to.writeInbound(bytes)
                    to.runPendingTasks()
                }
            }
            repeat(4) {
                transfer(client, server)
                transfer(server, client)
            }
            assertThat(omittedMessage).isTrue()
            assertThat(clientReady.isDone).isFalse()
            assertThat(serverReady.isDone).isFalse()
            // Paired in-process channels deliver parent closure to their peer here.
            server.closeFuture().addListener { client.close() }
            server.advanceTimeBy(HandshakeTimeoutSec.toLong(), TimeUnit.SECONDS)
            server.runScheduledPendingTasks()
            server.runPendingTasks()
            client.runPendingTasks()
            fun result(future: CompletableFuture<*>): Throwable {
                return try {
                    future.get(5, TimeUnit.SECONDS)
                    error("Expected an exceptional setup result")
                } catch (cause: ExecutionException) {
                    cause.cause!!
                }
            }
            assertThat(result(serverReady)).isInstanceOf(ReadTimeoutException::class.java)
            assertThat(result(clientReady)).isInstanceOf(ConnectionClosedException::class.java)
            assertThat(events).hasSize(2)
            assertThat(events[0]).startsWith("responder:")
            assertThat(events[1]).startsWith("initiator:")
            println("Timer callback order: $events")
            assertThat(client.isOpen).isFalse()
            assertThat(server.isOpen).isFalse()
        } finally {
            client.finishAndReleaseAll()
            server.finishAndReleaseAll()
        }
    }
}
