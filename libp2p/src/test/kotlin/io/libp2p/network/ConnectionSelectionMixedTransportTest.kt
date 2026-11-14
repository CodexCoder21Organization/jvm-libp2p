package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.EchoController
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class ConnectionSelectionMixedTransportTest {
    @Test
    fun `a non coordinating parent keeps peer knowledge alive for a pending dial`() {
        val key = generateKeyPair(KeyType.ED25519).first
        // Public construction used by transports predating coordinated connection selection.
        val legacyUpgrader = ConnectionUpgrader(
            MultistreamProtocolV1,
            listOf(NoiseXXSecureChannel(key)),
            MultistreamProtocolV1,
            listOf(StreamMuxerProtocol.getYamux().createMuxer(MultistreamProtocolV1, listOf(Echo())))
        )
        lateinit var transport: MixedSelectionTransport
        val a = host {
            identity { factory = { key } }
            transports { add { MixedSelectionTransport(it, legacyUpgrader).also { transport = it } } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(Echo()) }
        }
        val b = host {
            network { repeat(4) { listen("/ip4/127.0.0.${it + 1}/tcp/0") } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers {
                add(
                    StreamMuxerProtocol { multistream, protocols ->
                        StreamMuxerProtocol.getYamux().createMuxer(
                            multistream,
                            protocols.filterNot { it.protocolDescriptor.matchesAny(listOf("/libp2p/connection-selection/1.0.0")) }
                        )
                    }
                )
            }
            protocols { add(Echo()) }
        }
        val received = AtomicInteger()
        val closedControls = CopyOnWriteArrayList<CompletableFuture<Unit>>()
        b.addStreamVisitor(ChannelVisitor { received.incrementAndGet() })
        a.addStreamVisitor(
            ChannelVisitor { stream ->
                val done = CompletableFuture<Unit>()
                closedControls.add(done)
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun channelUnregistered(ctx: ChannelHandlerContext) {
                        ctx.fireChannelUnregistered()
                        ctx.executor().execute { done.complete(Unit) }
                    }
                })
            }
        )
        try {
            a.start().get(10, SECONDS)
            b.start().get(10, SECONDS)
            val connect = a.network.connect(b.peerId, *b.listenAddresses().toTypedArray())
            val initial = transport.firstThree.get(10, SECONDS)
            val legacy = initial.single { it.parent.transport() === transport.legacy }
            legacy.result.complete(legacy.parent)
            assertThat(connect.get(10, SECONDS)).isSameAs(legacy.parent)
            initial.filterNot { it === legacy }.forEach { it.result.complete(it.parent) }
            initial.forEach { (it.parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS) }
            closedControls.toList().forEach { it.get(10, SECONDS) }
            assertThat(received.get()).isEqualTo(1)
            initial.filterNot { it === legacy }.forEach {
                it.parent.close().get(10, SECONDS)
                it.parent.closeFuture().get(10, SECONDS)
                (it.parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS)
            }
            assertThat(a.network.connections).containsExactly(legacy.parent)
            transport.lastStart.complete(Unit)
            val last = transport.lastDelivered.get(10, SECONDS)
            (last as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS)
            closedControls.toList().forEach { it.get(10, SECONDS) }
            assertThat(received.get())
                .describedAs("The surviving non-coordinating parent must preserve the learned peer capability")
                .isEqualTo(1)
            assertThat(a.network.reserveConnection(last).get(10, SECONDS)).isTrue()
            val echo = a.newStream<EchoController>(listOf("/test/echo"), last).controller.get(10, SECONDS)
            assertThat(echo.echo("same connected peer").get(10, SECONDS)).isEqualTo("same connected peer")
        } finally {
            transport.start.complete(Unit)
            transport.lastStart.complete(Unit)
            try {
                a.stop().get(10, SECONDS)
            } finally {
                try {
                    transport.legacy.close().get(10, SECONDS)
                } finally {
                    b.stop().get(10, SECONDS)
                }
            }
            (a.network.transports + b.network.transports + transport.legacy).forEach {
                assertThat(it.activeConnections).isZero()
                assertThat(it.activeListeners).isZero()
            }
        }
    }
}

private class MixedSelectionDial(val parent: Connection, val result: CompletableFuture<Connection>)

private class MixedSelectionTransport(upgrader: ConnectionUpgrader, legacyUpgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val legacy = TcpTransport(legacyUpgrader).also { it.initialize() }
    val start = CompletableFuture<Unit>()
    val lastStart = CompletableFuture<Unit>()
    val firstThree = CompletableFuture<List<MixedSelectionDial>>()
    val lastDelivered = CompletableFuture<Connection>()
    private val initial = CopyOnWriteArrayList<MixedSelectionDial>()
    private val count = AtomicInteger()
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> {
        val index = count.incrementAndGet()
        val result = CompletableFuture<Connection>()
        val ready = if (index == 4) lastStart else start
        ready.thenCompose {
            if (index == 1) legacy.dial(addr, connHandler, preHandler) else super.dial(addr, connHandler, preHandler)
        }.whenComplete { parent, failure ->
            if (failure != null) {
                firstThree.completeExceptionally(failure)
                result.completeExceptionally(failure)
                lastDelivered.completeExceptionally(failure)
            } else if (index == 4) {
                result.complete(parent)
                lastDelivered.complete(parent)
            } else {
                initial.add(MixedSelectionDial(parent, result))
                if (initial.size == 3) firstThree.complete(initial.toList())
            }
        }
        if (index == 4) start.complete(Unit)
        return result
    }
}
