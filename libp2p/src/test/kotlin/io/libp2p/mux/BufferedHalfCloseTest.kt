package io.libp2p.mux

import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.libp2p.mux.yamux.INITIAL_WINDOW_SIZE
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class BufferedHalfCloseTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `half closed peer receives queued reply before end of stream`(yamux: Boolean) {
        val accepted = CompletableFuture<Stream>()
        val binding = object : StrictProtocolBinding<Stream>(
            "/buffered-half-close/1.0.0",
            object : P2PChannelHandler<Stream> {
                override fun initChannel(ch: P2PChannel): CompletableFuture<Stream> {
                    val stream = ch as Stream
                    if (!stream.isInitiator) accepted.complete(stream)
                    return CompletableFuture.completedFuture(stream)
                }
            }
        ) {}
        val makeHost = {
            host {
                identity { factory = { generateKeyPair(KeyType.ED25519).first } }
                transports { +::TcpTransport }
                secureChannels { add(::NoiseXXSecureChannel) }
                muxers { +(if (yamux) StreamMuxerProtocol.getYamux() else StreamMuxerProtocol.Mplex) }
                network { listen("/ip4/127.0.0.1/tcp/0") }
                protocols { +binding }
            }
        }
        val server = makeHost()
        val client = makeHost()
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val outgoing = client.newStream<Stream>(
                listOf("/buffered-half-close/1.0.0"),
                server.peerId,
                server.listenAddresses().first()
            ).controller.get(10, TimeUnit.SECONDS)
            val incoming = accepted.get(10, TimeUnit.SECONDS)
            val receiverReady = CompletableFuture<ChannelHandlerContext>()
            val received = ByteArrayOutputStream()
            val endOfStream = CompletableFuture<ByteArray>()
            incoming.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun handlerAdded(ctx: ChannelHandlerContext) {
                    ctx.channel().config().isAutoRead = false
                    receiverReady.complete(ctx)
                }

                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    try {
                        val data = msg as ByteBuf
                        val bytes = ByteArray(data.readableBytes())
                        data.readBytes(bytes)
                        received.write(bytes)
                    } finally {
                        ReferenceCountUtil.release(msg)
                    }
                }

                override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                    if (evt === RemoteWriteClosed) endOfStream.complete(received.toByteArray())
                    ctx.fireUserEventTriggered(evt)
                }
            })
            val receiverContext = receiverReady.get(10, TimeUnit.SECONDS)
            val remoteEnd = CompletableFuture<Unit>()
            val senderReady = CompletableFuture<ChannelHandlerContext>()
            outgoing.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun handlerAdded(ctx: ChannelHandlerContext) {
                    senderReady.complete(ctx)
                }

                override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                    if (evt === RemoteWriteClosed) remoteEnd.complete(Unit)
                    ctx.fireUserEventTriggered(evt)
                }
            })
            val senderContext = senderReady.get(10, TimeUnit.SECONDS)
            incoming.closeWrite().get(10, TimeUnit.SECONDS)
            remoteEnd.get(10, TimeUnit.SECONDS)

            val first = ByteArray(3 * INITIAL_WINDOW_SIZE + 37) { (it % 251).toByte() }
            val second = ByteArray(INITIAL_WINDOW_SIZE + 17) { ((it + 53) % 251).toByte() }
            val firstWrite = outgoing.writeAndFlushWithFuture(Unpooled.wrappedBuffer(first))
            val secondWrite = outgoing.writeAndFlushWithFuture(Unpooled.wrappedBuffer(second))
            senderContext.executor().submit {}.get(10, TimeUnit.SECONDS)
            if (yamux) assertFalse(firstWrite.isDone, "Paused peer must leave the reply waiting for window credit")

            outgoing.closeWrite().get(10, TimeUnit.SECONDS)
            if (yamux) {
                assertFalse(outgoing.closeFuture().isDone, "closeWrite must preserve the buffered reply until it drains")
                outgoing.closeWrite().get(10, TimeUnit.SECONDS)
            }
            val lateBuffer = Unpooled.buffer(1).writeByte(7)
            val lateWrite = outgoing.writeAndFlushWithFuture(lateBuffer)
            senderContext.executor().submit {}.get(10, TimeUnit.SECONDS)
            assertTrue(lateWrite.isCompletedExceptionally, "A write after closeWrite must fail")
            assertEquals(0, lateBuffer.refCnt(), "The rejected payload must be released")
            if (yamux) {
                val failure = assertThrows(ExecutionException::class.java) { lateWrite.get(10, TimeUnit.SECONDS) }
                assertEquals(
                    "The stream was closed for writing locally: ${senderContext.channel().id()}",
                    failure.cause!!.message
                )
            }

            receiverContext.executor().submit { receiverContext.channel().config().isAutoRead = true }
                .get(10, TimeUnit.SECONDS)
            assertArrayEquals(first + second, endOfStream.get(10, TimeUnit.SECONDS), "EOF must follow every accepted byte")
            firstWrite.get(10, TimeUnit.SECONDS)
            secondWrite.get(10, TimeUnit.SECONDS)
            outgoing.closeFuture().get(10, TimeUnit.SECONDS)
            incoming.closeFuture().get(10, TimeUnit.SECONDS)
        } finally {
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                server.stop().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
