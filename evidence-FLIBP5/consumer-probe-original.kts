@file:WithArtifact("foundation.url.protocol.buildMaven()")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-test:1.8.22")
@file:WithArtifact("community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-42")
package foundation.url.protocol

import build.kotlin.withartifact.WithArtifact
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

fun testR992FailedListenerCloseStillEndsAcceptorLoop() {
    val server = Libp2pHostFactory.createServerHost(
        listenAddresses = listOf("/ip4/127.0.0.1/tcp/0"),
        requestHandler = object : RpcRequestHandler {
            override suspend fun handleRequest(request: Libp2pRpcProtocol.RpcRequest, effectHandler: EffectPropagator) =
                Libp2pRpcProtocol.RpcResponse.success(request.id, "reply")
        },
    )
    val client = Libp2pHostFactory.createHost(listenAddresses = listOf("/ip4/127.0.0.1/tcp/0"))
    var listener: Channel? = null
    var stopped = false
    try {
        server.start().get(10, TimeUnit.SECONDS)
        client.start().get(10, TimeUnit.SECONDS)
        client.newStream<Libp2pRpcController>(listOf(Libp2pRpcProtocol.PROTOCOL_ID), server.peerId, server.listenAddresses().first())
            .controller.get(10, TimeUnit.SECONDS)
        val acceptor = (server.network.connections.single() as P2PChannelOverNetty).nettyChannel.parent()
        listener = acceptor
        val failure = IllegalStateException("The listener close failed before acceptor-loop cleanup")
        acceptor.pipeline().addFirst("r992-refuse-listener-close", object : ChannelOutboundHandlerAdapter() {
            override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) { promise.tryFailure(failure) }
        })
        stopped = true
        assertFailsWith<ExecutionException> { server.stop().get(10, TimeUnit.SECONDS) }
        // The failed channel result may remain retained. The per-host acceptor loop still needs
        // its own terminal cleanup; a failed channel promise must not skip that separate resource.
        assertTrue(acceptor.eventLoop().isShuttingDown, "A reported stop failure must still start cleanup of the owned acceptor event loop")
    } finally {
        listener?.let { acceptor ->
            acceptor.pipeline().context("r992-refuse-listener-close")?.let { acceptor.pipeline().remove(it.handler()) }
            acceptor.close().syncUninterruptibly()
            acceptor.eventLoop().shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
        }
        try { client.stop().get(10, TimeUnit.SECONDS) } finally {
            if (!stopped) server.stop().get(10, TimeUnit.SECONDS)
        }
    }
}
