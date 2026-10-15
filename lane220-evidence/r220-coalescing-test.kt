package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.security.SecureChannel
import io.libp2p.protocol.Ping
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises real plaintext handshake records and the next protocol header in one transport write. */
class PlaintextMuxCoalescingReviewTest {
    @ParameterizedTest
    @ValueSource(strings = ["mplex", "yamux"])
    fun coalescedPlaintextIdentityAndMuxHeaderPreserveNegotiation(muxerName: String) {
        val muxer = if (muxerName == "mplex") StreamMuxerProtocol.Mplex else StreamMuxerProtocol.getYamux()
        val announces = AtomicInteger()
        val activations = AtomicInteger()
        val batch = HandshakeWriteBatch()
        val client = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { add(::PlaintextInsecureChannel) }
            muxers { +muxer }
            protocols { +Ping() }
            debug {
                afterSecureHandler.addNettyHandler(object : ChannelDuplexHandler() {
                    override fun channelActive(ctx: ChannelHandlerContext) {
                        activations.incrementAndGet()
                        ctx.fireChannelActive()
                    }
                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                        if (msg is ByteBuf && msg.toString(Charsets.UTF_8).contains("/multistream/1.0.0")) {
                            println("CLIENT ROOT ANNOUNCE ${announces.incrementAndGet()} active=${activations.get()}")
                        }
                        ctx.write(msg, promise)
                    }
                })
            }
        }
        val server = host {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels {
                add { key, _ ->
                    val plaintext = PlaintextInsecureChannel(key)
                    object : SecureChannel by plaintext {
                        override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out SecureChannel.Session> {
                            ch.pushHandler(batch)
                            return plaintext.initChannel(ch, selectedProtocol)
                        }
                    }
                }
            }
            muxers { +muxer }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { +Ping() }
            debug {
                afterSecureHandler.addNettyHandler(object : ChannelDuplexHandler() {
                    override fun channelActive(ctx: ChannelHandlerContext) {
                        ctx.fireChannelActive()
                        batch.releaseBatch()
                    }
                })
            }
        }
        try {
            server.start().get(5, TimeUnit.SECONDS)
            client.start().get(5, TimeUnit.SECONDS)
            val promise = client.newStream<io.libp2p.protocol.PingController>(
                Ping().protocolDescriptor.announceProtocols,
                server.peerId,
                *server.listenAddresses().toTypedArray()
            )
            try {
                promise.controller.get(5, TimeUnit.SECONDS).ping().get(5, TimeUnit.SECONDS)
            } finally {
                println("ROOT COUNTS announces=${announces.get()} activations=${activations.get()} batchWrites=${batch.writeCount}")
            }
            assertEquals(1, announces.get(), "A coalesced handshake must send the root multistream header once")
        } finally {
            try {
                client.stop().get(5, TimeUnit.SECONDS)
            } finally {
                server.stop().get(5, TimeUnit.SECONDS)
            }
        }
    }
}

private class HandshakeWriteBatch : ChannelDuplexHandler() {
    private lateinit var context: ChannelHandlerContext
    private val buffers = mutableListOf<ByteBuf>()
    private val promises = mutableListOf<ChannelPromise>()
    private var released = false
    var writeCount = 0
        private set
    override fun handlerAdded(ctx: ChannelHandlerContext) {
        context = ctx
    }
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (released) {
            ctx.write(msg, promise)
        } else {
            buffers += msg as ByteBuf
            promises += promise
            writeCount++
        }
    }
    override fun flush(ctx: ChannelHandlerContext) {
        if (released) ctx.flush()
    }
    fun releaseBatch() {
        if (released) return
        released = true
        val writes = promises.toList()
        val composite = Unpooled.wrappedBuffer(*buffers.toTypedArray())
        buffers.clear()
        promises.clear()
        context.writeAndFlush(composite).addListener { future ->
            writes.forEach { if (future.isSuccess) it.trySuccess() else it.tryFailure(future.cause()) }
        }
    }
    override fun channelInactive(ctx: ChannelHandlerContext) {
        buffers.forEach(ReferenceCountUtil::release)
        buffers.clear()
        promises.forEach { it.tryFailure(ConnectionClosedException()) }
        promises.clear()
        ctx.fireChannelInactive()
    }
}
