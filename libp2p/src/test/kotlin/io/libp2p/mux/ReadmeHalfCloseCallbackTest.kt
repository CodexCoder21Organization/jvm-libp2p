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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReadmeHalfCloseCallbackTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `README callback sends a large reply and end of stream`(yamux: Boolean) {
        val accepted = CompletableFuture<Stream>()
        val binding = object : StrictProtocolBinding<Stream>(
            "/readme-half-close/1.0.0",
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
                listOf("/readme-half-close/1.0.0"),
                server.peerId,
                server.listenAddresses().first()
            ).controller.get(10, TimeUnit.SECONDS)
            val incoming = accepted.get(10, TimeUnit.SECONDS)
            val reply = ByteArray(3 * INITIAL_WINDOW_SIZE + 37) { (it % 251).toByte() }
            val received = ByteArrayOutputStream()
            val eof = CompletableFuture<ByteArray>()
            val readerReady = CompletableFuture<Unit>()
            outgoing.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun handlerAdded(ctx: ChannelHandlerContext) {
                    readerReady.complete(Unit)
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
                    if (evt === RemoteWriteClosed) eof.complete(received.toByteArray())
                    ctx.fireUserEventTriggered(evt)
                }
            })
            val responderReady = CompletableFuture<Unit>()
            val callbackOnEventLoop = CompletableFuture<Boolean>()
            val callbackReturned = CountDownLatch(1)
            val replyFinished = CompletableFuture<Unit>()
            incoming.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun handlerAdded(ctx: ChannelHandlerContext) {
                    responderReady.complete(Unit)
                }

                override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                    if (evt === RemoteWriteClosed) {
                        callbackOnEventLoop.complete(ctx.executor().inEventLoop())
                        readmeReplyAndCloseWrite(incoming, reply).whenComplete { _, failure ->
                            if (failure == null) replyFinished.complete(Unit) else replyFinished.completeExceptionally(failure)
                        }
                        callbackReturned.countDown()
                    }
                    ctx.fireUserEventTriggered(evt)
                }
            })
            readerReady.get(10, TimeUnit.SECONDS)
            responderReady.get(10, TimeUnit.SECONDS)
            outgoing.closeWrite().get(10, TimeUnit.SECONDS)
            assertTrue(callbackOnEventLoop.get(10, TimeUnit.SECONDS), "The reply must originate on the protocol event loop")
            assertTrue(callbackReturned.await(3, TimeUnit.SECONDS), "The README reply helper must return without blocking the event loop")
            assertArrayEquals(reply, eof.get(10, TimeUnit.SECONDS), "EOF must follow the complete callback reply")
            replyFinished.get(10, TimeUnit.SECONDS)
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

private fun readmeReplyAndCloseWrite(stream: Stream, reply: ByteArray): CompletableFuture<Unit> {
    val write = stream.writeAndFlushWithFuture(Unpooled.wrappedBuffer(reply))
    return stream.closeWrite().thenCompose { write }
}
