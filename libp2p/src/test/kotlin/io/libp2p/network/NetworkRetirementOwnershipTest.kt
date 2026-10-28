package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class NetworkRetirementOwnershipTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an older failed approval cannot release another retirement exclusion`(holdPeerPublication: Boolean) {
        val publication = CompletableFuture<Unit>()
        if (!holdPeerPublication) publication.complete(Unit)
        val registered = CompletableFuture<Unit>()
        val a = host {
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        val b = host {
            muxerMultistreamProtocol = RetirementPublicationProtocol(publication)
            network { listen("/ip4/127.0.0.1/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        b.addConnectionHandler(ConnectionHandler.create { registered.complete(Unit) })
        val held = CompletableFuture<() -> CompletableFuture<Unit>>()
        val reset = CompletableFuture<Unit>()
        val replies = AtomicInteger()
        b.addStreamVisitor(
            ChannelVisitor { stream ->
                stream.getProtocol().thenAccept { protocol ->
                    if (protocol == "/libp2p/connection-selection/1.0.0") {
                        stream.pushHandler(object : ChannelOutboundHandlerAdapter() {
                            override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                                if (msg is ByteBuf && msg.readableBytes() == 1 && replies.incrementAndGet() == 1) {
                                    assertThat(msg.getUnsignedByte(msg.readerIndex()).toInt()).isEqualTo(1)
                                    held.complete {
                                        val completed = CompletableFuture<Unit>()
                                        ctx.executor().execute {
                                            // Forward the original write to the genuinely closed child.
                                            ctx.writeAndFlush(msg, promise)
                                            promise.addListener { result ->
                                                assertThat(result.isSuccess).isFalse()
                                                ctx.executor().execute { completed.complete(Unit) }
                                            }
                                        }
                                        completed
                                    }
                                    stream.reset().whenComplete { _, failure ->
                                        if (failure == null) reset.complete(Unit) else reset.completeExceptionally(failure)
                                    }
                                } else {
                                    ctx.write(msg, promise)
                                }
                            }
                        })
                    }
                }
            }
        )
        var released = false
        try {
            a.start().get(10, SECONDS)
            b.start().get(10, SECONDS)
            val connectedBy = System.nanoTime() + SECONDS.toNanos(10)
            val parent = a.network.connect(b.peerId, *b.listenAddresses().toTypedArray()).get(10, SECONDS)
            if (holdPeerPublication) {
                assertThat(b.network.connections).isEmpty()
                assertThat(registered.isDone).isFalse()
            }
            publication.complete(Unit)
            // Local dial completion does not publish the independently scheduled peer.
            registered.get(maxOf(0L, connectedBy - System.nanoTime()), NANOSECONDS)
            assertThat(b.network.connections).hasSize(1)
            val remote = b.network.connections.single()
            val first = parent.muxerSession().createStream(listOf(OwnershipRetirementBinding()))
            val failed = first.controller.handle { _, failure -> failure }.get(10, SECONDS)
            assertThat(failed).isNotNull()
            reset.get(10, SECONDS)
            val release = held.get(10, SECONDS)
            // The second reply reaches the real initiator before the first write fails locally.
            val second = parent.muxerSession().createStream(listOf(OwnershipRetirementBinding()))
            val approved = second.controller.get(10, SECONDS)
            released = true
            release().get(10, SECONDS)
            val published = b.network.connect(a.peerId).handle { connection, _ -> connection }.get(10, SECONDS)
            if (approved) {
                // Obey the delivered retirement approval at its physical initiator.
                parent.close().get(10, SECONDS)
                remote.closeFuture().get(10, SECONDS)
                assertThat(published)
                    .describedAs("An acceptor must never publish a parent with a delivered retirement approval after an older reply fails")
                    .isNull()
            } else {
                assertThat(published).isSameAs(remote)
                val echo = b.newStream<EchoController>(listOf("/test/echo"), published).controller.get(10, SECONDS)
                assertThat(echo.echo("request ownership").get(10, SECONDS)).isEqualTo("request ownership")
                assertThat(remote.closeFuture().isDone).isFalse()
            }
            assertThat(replies.get()).isEqualTo(2)
        } finally {
            publication.complete(Unit)
            if (!released && held.isDone) held.get(10, SECONDS).invoke().get(10, SECONDS)
            try {
                a.stop().get(10, SECONDS)
            } finally {
                b.stop().get(10, SECONDS)
            }
            (a.network.transports + b.network.transports).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private class OwnershipRetirementBinding : StrictProtocolBinding<Boolean>(
    "/libp2p/connection-selection/1.0.0",
    object : ProtocolHandler<Boolean>(1, 1) {
        override fun onStartInitiator(stream: Stream): CompletableFuture<Boolean> {
            val reply = CompletableFuture<Boolean>()
            stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
                override fun onActivated(stream: Stream) {
                    stream.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(2)).whenComplete { _, failure ->
                        if (failure != null) reply.completeExceptionally(failure)
                    }
                }
                override fun onMessage(stream: Stream, msg: ByteBuf) {
                    assertThat(msg.readableBytes()).isEqualTo(1)
                    reply.complete(msg.readUnsignedByte().toInt() == 1)
                }
                override fun onClosed(stream: Stream) {
                    reply.completeExceptionally(ConnectionClosedException("Retirement child closed before its reply"))
                }
                override fun onException(cause: Throwable?) {
                    reply.completeExceptionally(cause ?: ConnectionClosedException("Retirement child failed before its reply"))
                }
            })
            return reply
        }
    }
)

private class RetirementPublicationProtocol(private val publication: CompletableFuture<Unit>) : MultistreamProtocol {
    override val version = MultistreamProtocolV1.version

    override fun <T> createMultistream(bindings: List<ProtocolBinding<T>>): Multistream<T> {
        val delegate = MultistreamProtocolV1.createMultistream(bindings)
        return object : Multistream<T> {
            override val bindings = delegate.bindings

            override fun initChannel(ch: P2PChannel): CompletableFuture<T> =
                delegate.initChannel(ch).thenCompose { controller ->
                    publication.thenApplyAsync(
                        { controller },
                        (ch as ConnectionOverNetty).nettyChannel.eventLoop()
                    )
                }
        }
    }
}
