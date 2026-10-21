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
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class StreamCloseWriteTest {
    @ParameterizedTest(name = "remote reset then closeWrite, yamux={0}")
    @ValueSource(booleans = [true, false])
    fun remoteResetThenCloseWrite(yamux: Boolean) {
        withCloseWriteStreams(yamux) { outgoing, incoming ->
            outgoing.reset().get(10, TimeUnit.SECONDS)
            incoming.closeFuture().get(10, TimeUnit.SECONDS)
            assertDoesNotThrow {
                incoming.closeWrite().get(10, TimeUnit.SECONDS)
            }
        }
    }

    @ParameterizedTest(name = "remote close then closeWrite, yamux={0}")
    @ValueSource(booleans = [true, false])
    fun remoteCloseThenCloseWrite(yamux: Boolean) {
        withCloseWriteStreams(yamux) { outgoing, incoming ->
            outgoing.close().get(10, TimeUnit.SECONDS)
            incoming.closeFuture().get(10, TimeUnit.SECONDS)
            assertDoesNotThrow { incoming.closeWrite().get(10, TimeUnit.SECONDS) }
        }
    }

    @ParameterizedTest(name = "local close then closeWrite, yamux={0}")
    @ValueSource(booleans = [true, false])
    fun localCloseThenCloseWrite(yamux: Boolean) {
        withCloseWriteStreams(yamux) { outgoing, _ ->
            outgoing.close().get(10, TimeUnit.SECONDS)
            assertDoesNotThrow { outgoing.closeWrite().get(10, TimeUnit.SECONDS) }
        }
    }

    @ParameterizedTest(name = "parent close then closeWrite, yamux={0}")
    @ValueSource(booleans = [true, false])
    fun parentCloseThenCloseWrite(yamux: Boolean) {
        withCloseWriteStreams(yamux, parentCloses = true) { outgoing, incoming ->
            outgoing.connection.close().get(10, TimeUnit.SECONDS)
            incoming.closeFuture().get(10, TimeUnit.SECONDS)
            outgoing.closeFuture().get(10, TimeUnit.SECONDS)
            assertDoesNotThrow { outgoing.closeWrite().get(10, TimeUnit.SECONDS) }
            assertDoesNotThrow { incoming.closeWrite().get(10, TimeUnit.SECONDS) }
        }
    }

    @ParameterizedTest(name = "remote half-close then closeWrite, yamux={0}")
    @ValueSource(booleans = [true, false])
    fun remoteHalfCloseThenCloseWrite(yamux: Boolean) {
        withCloseWriteStreams(yamux) { outgoing, incoming ->
            val end = observeWriteEnd(incoming)
            outgoing.closeWrite().get(10, TimeUnit.SECONDS)
            assertArrayEquals(byteArrayOf(), end.get(10, TimeUnit.SECONDS))
            assertFalse(incoming.closeFuture().isDone)
            incoming.closeWrite().get(10, TimeUnit.SECONDS)
            incoming.closeFuture().get(10, TimeUnit.SECONDS)
            outgoing.closeFuture().get(10, TimeUnit.SECONDS)
            assertDoesNotThrow { incoming.closeWrite().get(10, TimeUnit.SECONDS) }
        }
    }

    @ParameterizedTest(name = "live write close keeps reply direction open, yamux={0}")
    @ValueSource(booleans = [true, false])
    fun liveWriteCloseAllowsReply(yamux: Boolean) {
        withCloseWriteStreams(yamux) { outgoing, incoming ->
            val request = ByteArray(1024) { it.toByte() }
            val reply = ByteArray(513) { (it + 7).toByte() }
            val requestEnd = observeWriteEnd(incoming)
            val replyEnd = observeWriteEnd(outgoing)
            outgoing.writeAndFlushWithFuture(Unpooled.wrappedBuffer(request)).get(10, TimeUnit.SECONDS)
            outgoing.closeWrite().get(10, TimeUnit.SECONDS)
            assertArrayEquals(request, requestEnd.get(10, TimeUnit.SECONDS))
            assertFalse(incoming.closeFuture().isDone)
            assertFalse(outgoing.closeFuture().isDone)
            assertDoesNotThrow { outgoing.closeWrite().get(10, TimeUnit.SECONDS) }
            incoming.writeAndFlushWithFuture(Unpooled.wrappedBuffer(reply)).get(10, TimeUnit.SECONDS)
            incoming.closeWrite().get(10, TimeUnit.SECONDS)
            assertArrayEquals(reply, replyEnd.get(10, TimeUnit.SECONDS))
            incoming.closeFuture().get(10, TimeUnit.SECONDS)
            outgoing.closeFuture().get(10, TimeUnit.SECONDS)
        }
    }

    @ParameterizedTest(name = "close callback can close writing, yamux={0}")
    @ValueSource(booleans = [true, false])
    fun closeWriteDuringInactiveCallback(yamux: Boolean) {
        withCloseWriteStreams(yamux) { outgoing, _ ->
            val installed = CompletableFuture<Unit>()
            val callbackClose = CompletableFuture<Unit>()
            outgoing.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun handlerAdded(ctx: ChannelHandlerContext) {
                    installed.complete(Unit)
                }

                override fun channelInactive(ctx: ChannelHandlerContext) {
                    outgoing.closeWrite().whenComplete { _, failure ->
                        if (failure == null) {
                            callbackClose.complete(Unit)
                        } else {
                            callbackClose.completeExceptionally(failure)
                        }
                    }
                    ctx.fireChannelInactive()
                }
            })
            installed.get(10, TimeUnit.SECONDS)
            outgoing.close().get(10, TimeUnit.SECONDS)
            assertDoesNotThrow { callbackClose.get(10, TimeUnit.SECONDS) }
        }
    }
}

private fun withCloseWriteStreams(
    yamux: Boolean,
    parentCloses: Boolean = false,
    scenario: (Stream, Stream) -> Unit
) {
    val protocolId = "/close-write-lifecycle/1.0.0"
    val incomingStream = CompletableFuture<Stream>()
    val binding = object : StrictProtocolBinding<Stream>(
        protocolId,
        object : P2PChannelHandler<Stream> {
            override fun initChannel(ch: P2PChannel): CompletableFuture<Stream> {
                val stream = ch as Stream
                if (!stream.isInitiator) incomingStream.complete(stream)
                return CompletableFuture.completedFuture(stream)
            }
        }
    ) {}
    val createHost = {
        host {
            identity { factory = { generateKeyPair(KeyType.ED25519).first } }
            transports { +::TcpTransport }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { +(if (yamux) StreamMuxerProtocol.getYamux() else StreamMuxerProtocol.Mplex) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { +binding }
        }
    }
    val server = createHost()
    val client = createHost()
    var primary: Throwable? = null
    try {
        server.start().get(10, TimeUnit.SECONDS)
        client.start().get(10, TimeUnit.SECONDS)
        val opened = client.newStream<Stream>(listOf(protocolId), server.peerId, server.listenAddresses().first())
        val outgoing = opened.controller.get(10, TimeUnit.SECONDS)
        val incoming = incomingStream.get(10, TimeUnit.SECONDS)
        scenario(outgoing, incoming)
        if (parentCloses) {
            assertTrue(outgoing.connection.closeFuture().isDone)
        } else {
            assertFalse(outgoing.connection.closeFuture().isDone, "Closing a child must leave its connection usable")
            val next = client.newStream<Stream>(listOf(protocolId), outgoing.connection)
            val nextStream = next.controller.get(10, TimeUnit.SECONDS)
            assertSame(outgoing.connection, nextStream.connection)
            nextStream.close().get(10, TimeUnit.SECONDS)
        }
    } catch (failure: Throwable) {
        primary = failure
    } finally {
        for (host in listOf(client, server)) {
            try {
                host.stop().get(10, TimeUnit.SECONDS)
            } catch (failure: Throwable) {
                val earlier = primary
                if (earlier == null) primary = failure else earlier.addSuppressed(failure)
            }
        }
    }
    primary?.let { throw it }
}

private fun observeWriteEnd(stream: Stream): CompletableFuture<ByteArray> {
    val installed = CompletableFuture<Unit>()
    val end = CompletableFuture<ByteArray>()
    val bytes = ByteArrayOutputStream()
    stream.pushHandler(object : ChannelInboundHandlerAdapter() {
        override fun handlerAdded(ctx: ChannelHandlerContext) {
            installed.complete(Unit)
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) {
                try {
                    val payload = ByteArray(msg.readableBytes())
                    msg.readBytes(payload)
                    bytes.write(payload)
                } finally {
                    msg.release()
                }
            } else {
                ctx.fireChannelRead(msg)
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt === RemoteWriteClosed) end.complete(bytes.toByteArray())
            ctx.fireUserEventTriggered(evt)
        }

        @Deprecated("Deprecated in Java")
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            end.completeExceptionally(cause)
            ctx.fireExceptionCaught(cause)
        }
    })
    installed.get(10, TimeUnit.SECONDS)
    return end
}
