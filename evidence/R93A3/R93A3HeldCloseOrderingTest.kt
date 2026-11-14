package io.libp2p.transport

import io.libp2p.core.dsl.host
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.concurrent.DefaultPromise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class R93A3HeldCloseOrderingTest {
    @Test
    fun heldCloseKeepsEarlierPhysicalListenersAheadOfItsCompletion() {
        val server = host { network { listen("/ip4/127.0.0.1/tcp/0") } }
        val client = host { }
        val held = CompletableFuture<Pair<ChannelHandlerContext, ChannelPromise>>()
        val events = CopyOnWriteArrayList<String>()
        try {
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val connection = client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
                .get(10, TimeUnit.SECONDS) as ConnectionOverNetty
            val channel = connection.nettyChannel
            val loop = channel.eventLoop()
            loop.submit {
                channel.closeFuture().addListener { events += "physical close listener" }
                channel.pipeline().addFirst("hold-original-close", object : ChannelOutboundHandlerAdapter() {
                    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
                        check(held.complete(ctx to promise)) { "Only one close may reach this handler" }
                    }
                })
            }.get(10, TimeUnit.SECONDS)
            val operation = connection.close()
            val completed = operation.thenRun { events += "close operation" }
            val request = held.get(10, TimeUnit.SECONDS)
            loop.submit {
                val chain = List(8) { DefaultPromise<Void>(loop) }
                for (index in 0 until chain.lastIndex) {
                    chain[index].addListener { chain[index + 1].setSuccess(null) }
                }
                chain.last().addListener { request.first.close(request.second) }
                chain.first().setSuccess(null)
            }.get(10, TimeUnit.SECONDS)
            completed.get(10, TimeUnit.SECONDS)
            connection.closeFuture().get(10, TimeUnit.SECONDS)
            loop.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("physical close listener", "close operation"), events,
                "An earlier physical-close listener must remain ahead of the held close result")
        } finally {
            held.getNow(null)?.let { (ctx, promise) -> if (!promise.isDone) ctx.close(promise) }
            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
        }
    }
}
