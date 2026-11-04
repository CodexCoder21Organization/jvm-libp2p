package io.libp2p.protocol

import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.tls.TlsSecureChannel
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PingBufferOwnershipTest {
    @Test
    fun largeTlsPingPreservesRetainedPayloadWhileEchoWriteCompletes() {
        val retained = CopyOnWriteArrayList<Pair<ByteBuf, ByteArray>>()
        val responderStream = CompletableFuture<Stream>()
        val serverProtocol = object : PingProtocol(200 * 1024) {
            override fun onStartResponder(stream: Stream): CompletableFuture<PingController> {
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        if (msg is ByteBuf) {
                            retained.add(msg.retain() to ByteBufUtil.getBytes(msg))
                        }
                        ctx.fireChannelRead(msg)
                    }
                })
                return super.onStartResponder(stream).also { responderStream.complete(stream) }
            }
        }
        fun endpoint(binding: PingBinding, listen: Boolean): Host = host {
            transports { add(::TcpTransport) }
            secureChannels { add(TlsSecureChannel::ECDSA) }
            muxers { +StreamMuxerProtocol.getYamux() }
            protocols { +binding }
            if (listen) network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val server = endpoint(PingBinding(serverProtocol), true)
        val client = endpoint(Ping(200 * 1024), false)
        val hold = AtomicBoolean(true)
        val completions = CopyOnWriteArrayList<ChannelPromise>()
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val opened = client.newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"), server.peerId, *server.listenAddresses().toTypedArray()
            )
            val controller = opened.controller.get(10, TimeUnit.SECONDS)
            val stream = responderStream.get(10, TimeUnit.SECONDS)
            val parent = (stream.connection as P2PChannelOverNetty).nettyChannel
            parent.eventLoop().submit {
                parent.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                        // Send the real encrypted bytes, but keep the caller's completion pending.
                        // A socket write is allowed to finish after its protocol callback returns.
                        ctx.write(msg, ctx.newPromise().apply {
                            addListener { result ->
                                if (!result.isSuccess) promise.tryFailure(result.cause())
                                else if (hold.get()) completions.add(promise)
                                else promise.trySuccess()
                            }
                        })
                    }
                })
            }.get(10, TimeUnit.SECONDS)
            controller.ping().get(10, TimeUnit.SECONDS)
            parent.eventLoop().submit {
                assertFalse(completions.isEmpty(), "The echo must have a pending real transport completion")
                hold.set(false)
                val pending = completions.toList()
                completions.clear()
                pending.forEach { it.trySuccess() }
            }.get(10, TimeUnit.SECONDS)
            assertEquals(1, retained.size, "One complete Yamux payload must reach the Ping responder")
            retained.forEach { (buffer, bytes) ->
                assertEquals(1, buffer.refCnt(), "The observer's retained payload must survive echo completion")
                assertArrayEquals(bytes, ByteBufUtil.getBytes(buffer))
            }
            controller.ping().get(10, TimeUnit.SECONDS)
            assertEquals(2, retained.size, "The same TLS/Yamux connection must remain usable")
        } finally {
            hold.set(false)
            completions.forEach { it.trySuccess() }
            try {
                client.stop().get(10, TimeUnit.SECONDS)
            } finally {
                try {
                    server.stop().get(10, TimeUnit.SECONDS)
                } finally {
                    // A failing ownership assertion can observe an already-released buffer.
                    retained.forEach { (buffer, _) -> if (buffer.refCnt() > 0) buffer.release() }
                }
            }
        }
    }
}
