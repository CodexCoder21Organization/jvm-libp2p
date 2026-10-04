package io.libp2p.security.noise

import io.libp2p.core.Connection
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import io.libp2p.tools.TestLogAppender
import org.junit.jupiter.api.Assertions.assertFalse
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class NoiseHandshakeWriteFailureTest {
    @RepeatedTest(20)
    fun `failed Noise write completes connect with the write failure and closes transport`() {
        val writeFailure = IOException("The test transport refused the Noise handshake frame")
        val refusedWrite = CompletableFuture<Unit>()
        val dialChannel = CompletableFuture<Connection>()
        val listener = host {
            identity { random(KeyType.ED25519) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val dialer = host {
            identity { random(KeyType.ED25519) }
            debug {
                beforeSecureHandler.addHandler { connection ->
                    dialChannel.complete(connection)
                    connection.pushHandler(object : ChannelOutboundHandlerAdapter() {
                        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                            // Multistream selection frames are shorter than the first Noise frame.
                            // Refuse the actual write at the transport boundary, before any bytes enter it.
                            if (msg is ByteBuf && msg.readableBytes() >= 32) {
                                ReferenceCountUtil.release(msg)
                                promise.setFailure(writeFailure)
                                refusedWrite.complete(Unit)
                            } else {
                                ctx.write(msg, promise)
                            }
                        }
                    })
                }
            }
        }
        try {
            listener.start().get(5, TimeUnit.SECONDS)
            dialer.start().get(5, TimeUnit.SECONDS)
            val connected = dialer.network.connect(listener.peerId, listener.listenAddresses().single())
            refusedWrite.get(5, TimeUnit.SECONDS)
            val failure = try {
                connected.get(2, TimeUnit.SECONDS)
                throw AssertionError("A refused Noise write must fail the public connect operation")
            } catch (failure: ExecutionException) {
                failure
            }
            assertSame(writeFailure, failure.cause, "The connect caller must receive the original write failure")
            dialChannel.get(5, TimeUnit.SECONDS).closeFuture().get(2, TimeUnit.SECONDS)
        } finally {
            dialer.stop().get(5, TimeUnit.SECONDS)
            listener.stop().get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `late failed Noise write after close does not reach the pipeline tail`() {
        val heldWrite = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
        val dialChannel = CompletableFuture<Connection>()
        val listener = host {
            identity { random(KeyType.ED25519) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
        val dialer = host {
            identity { random(KeyType.ED25519) }
            debug {
                beforeSecureHandler.addHandler { connection ->
                    dialChannel.complete(connection)
                    connection.pushHandler(object : ChannelOutboundHandlerAdapter() {
                        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                            if (msg is ByteBuf && msg.readableBytes() >= 32) {
                                ReferenceCountUtil.release(msg)
                                heldWrite.complete(ctx to promise)
                            } else {
                                ctx.write(msg, promise)
                            }
                        }
                    })
                }
            }
        }
        try {
            listener.start().get(5, TimeUnit.SECONDS)
            dialer.start().get(5, TimeUnit.SECONDS)
            val connected = dialer.network.connect(listener.peerId, listener.listenAddresses().single())
            val (ctx, promise) = heldWrite.get(5, TimeUnit.SECONDS)
            dialChannel.get(5, TimeUnit.SECONDS).close().get(2, TimeUnit.SECONDS)
            // Observe the public operation's terminal state before delivering the late write result.
            try {
                connected.get(2, TimeUnit.SECONDS)
                throw AssertionError("Closing the transport must fail the pending connect operation")
            } catch (_: ExecutionException) {
                // A closed connection cannot complete a pending handshake successfully.
            }
            TestLogAppender().install().use { logs ->
                ctx.executor().submit {
                    promise.setFailure(IOException("The closed transport finished its pending Noise write"))
                }.get(2, TimeUnit.SECONDS)
                assertFalse(logs.hasAnyWarns(), logs.logs.joinToString("\n") { it.message.formattedMessage })
            }
        } finally {
            dialer.stop().get(5, TimeUnit.SECONDS)
            listener.stop().get(5, TimeUnit.SECONDS)
        }
    }

}
