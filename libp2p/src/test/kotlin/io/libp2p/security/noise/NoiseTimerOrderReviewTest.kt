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
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class NoiseTimerOrderReviewTest {
    @Test
    fun testResponderTimerCanSetConnectionCloseResult() {
        val (clientKey, clientPublic) = generateKeyPair(KeyType.ED25519)
        val (serverKey, serverPublic) = generateKeyPair(KeyType.ED25519)
        val client = TestChannel("timer-order-client", true, remotePeerId = PeerId.fromPubKey(serverPublic))
        val server = TestChannel("timer-order-server", false, remotePeerId = PeerId.fromPubKey(clientPublic))
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
            val multistream = MultistreamProtocolDebugV1()
            val clientReady = multistream.createMultistream(listOf(NoiseXXSecureChannel(clientKey)))
                .initChannel(client.attr(CONNECTION).get())
            val serverReady = multistream.createMultistream(listOf(NoiseXXSecureChannel(serverKey)))
                .initChannel(server.attr(CONNECTION).get())
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
            assertThat(client.isOpen).isFalse()
            assertThat(server.isOpen).isFalse()
        } finally {
            client.finishAndReleaseAll()
            server.finishAndReleaseAll()
        }
    }
}
