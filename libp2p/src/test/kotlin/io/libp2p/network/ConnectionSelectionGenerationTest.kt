package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
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

class ConnectionSelectionGenerationTest {
    @Test
    fun `last close callback registers an upgraded generation before old cleanup`() {
        val key = generateKeyPair(KeyType.ED25519).first
        val old = host {
            identity { factory = { key } }
            network { repeat(3) { listen("/ip4/127.0.0.${it + 1}/tcp/0") } }
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
        }
        val upgraded = host {
            identity { factory = { key } }
            network { repeat(3) { listen("/ip4/127.0.0.${it + 1}/tcp/0") } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
        }
        lateinit var transport: ConnectionSelectionGenerationTransport
        val a = host {
            transports { add { ConnectionSelectionGenerationTransport(it).also { transport = it } } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
        }
        val oldRequests = AtomicInteger()
        val newRequests = AtomicInteger()
        val controls = CopyOnWriteArrayList<CompletableFuture<Unit>>()
        old.addStreamVisitor(ChannelVisitor { oldRequests.incrementAndGet() })
        upgraded.addStreamVisitor(ChannelVisitor { newRequests.incrementAndGet() })
        a.addStreamVisitor(
            ChannelVisitor { stream ->
                val done = CompletableFuture<Unit>()
                controls.add(done)
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
            old.start().get(10, SECONDS)
            upgraded.start().get(10, SECONDS)
            assertThat(upgraded.peerId).isEqualTo(old.peerId)
            val result = a.network.connect(old.peerId, *(old.listenAddresses() + upgraded.listenAddresses()).toTypedArray())
            val initial = transport.initial.get(10, SECONDS)
            val later = transport.later.get(10, SECONDS)
            initial.first().result.complete(initial.first().parent)
            assertThat(result.get(10, SECONDS)).isSameAs(initial.first().parent)
            initial.drop(1).forEach { it.result.complete(it.parent) }
            initial.forEach { (it.parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS) }
            controls.toList().forEach { it.get(10, SECONDS) }
            assertThat(oldRequests.get()).isEqualTo(1)
            initial.drop(1).forEach {
                it.parent.close().get(10, SECONDS)
                it.parent.closeFuture().get(10, SECONDS)
            }
            val registered = CompletableFuture<Unit>()
            initial.first().parent.closeFuture().thenRun {
                try {
                    // Already-upgraded real parents are delivered synchronously, so no event loop
                    // is blocked waiting for a socket or handshake while the old cleanup is pending.
                    later.forEach {
                        it.register.invoke()
                        it.result.complete(it.parent)
                    }
                    registered.complete(Unit)
                } catch (failure: Throwable) {
                    registered.completeExceptionally(failure)
                }
            }
            initial.first().parent.close().get(10, SECONDS)
            registered.get(10, SECONDS)
            later.drop(1).forEach { it.parent.closeFuture().get(10, SECONDS) }
            assertThat(newRequests.get()).isEqualTo(2)
            assertThat(later.first().parent.closeFuture().isDone).isFalse()
            assertThat(a.network.connect(old.peerId).get(10, SECONDS)).isSameAs(later.first().parent)
        } finally {
            transport.start.complete(Unit)
            transport.all.forEach { it.result.complete(it.parent) }
            try {
                a.stop().get(10, SECONDS)
            } finally {
                try {
                    old.stop().get(10, SECONDS)
                } finally {
                    upgraded.stop().get(10, SECONDS)
                }
            }
        }
    }

    @Test
    fun `supporting overlap extends absence after all original parents close`() {
        val key = generateKeyPair(KeyType.ED25519).first
        val old = host {
            identity { factory = { key } }
            network { repeat(3) { listen("/ip4/127.0.0.${it + 1}/tcp/0") } }
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
        }
        val upgraded = host {
            identity { factory = { key } }
            network { repeat(3) { listen("/ip4/127.0.0.${it + 1}/tcp/0") } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(io.libp2p.tools.Echo()) }
        }
        lateinit var transport: ConnectionSelectionGenerationTransport
        val a = host {
            transports { add { ConnectionSelectionGenerationTransport(it).also { transport = it } } }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(io.libp2p.tools.Echo()) }
        }
        val oldRequests = AtomicInteger()
        val newRequests = AtomicInteger()
        val controls = CopyOnWriteArrayList<CompletableFuture<Unit>>()
        old.addStreamVisitor(ChannelVisitor { oldRequests.incrementAndGet() })
        upgraded.addStreamVisitor(ChannelVisitor { newRequests.incrementAndGet() })
        a.addStreamVisitor(
            ChannelVisitor { stream ->
                val done = CompletableFuture<Unit>()
                controls.add(done)
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
            old.start().get(10, SECONDS)
            upgraded.start().get(10, SECONDS)
            assertThat(upgraded.peerId).isEqualTo(old.peerId)
            val result = a.network.connect(old.peerId, *(old.listenAddresses() + upgraded.listenAddresses()).toTypedArray())
            val initial = transport.initial.get(10, SECONDS)
            val later = transport.later.get(10, SECONDS)
            initial.first().result.complete(initial.first().parent)
            assertThat(result.get(10, SECONDS)).isSameAs(initial.first().parent)
            initial.drop(1).forEach { it.result.complete(it.parent) }
            initial.forEach { (it.parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS) }
            controls.toList().forEach { it.get(10, SECONDS) }
            assertThat(oldRequests.get()).isEqualTo(1)
            later.forEach {
                it.register.invoke()
                it.result.complete(it.parent)
            }
            later.forEach { (it.parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS) }
            assertThat(newRequests.get()).isZero()
            initial.forEach {
                it.parent.close().get(10, SECONDS)
                it.parent.closeFuture().get(10, SECONDS)
                (it.parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS)
            }
            a.network.holdConnection(later.last().parent).get(10, SECONDS)!!.close()
            later.forEach { (it.parent as ConnectionOverNetty).nettyChannel.eventLoop().submit {}.get(10, SECONDS) }
            assertThat(newRequests.get()).isZero()
            assertThat(later.all { !it.parent.closeFuture().isDone }).isTrue()
            val echo = a.newStream<io.libp2p.tools.EchoController>(listOf("/test/echo"), later.first().parent)
            assertThat(echo.controller.get(10, SECONDS).echo("overlapping upgrade").get(10, SECONDS))
                .isEqualTo("overlapping upgrade")
        } finally {
            transport.start.complete(Unit)
            transport.all.forEach { it.result.complete(it.parent) }
            try {
                a.stop().get(10, SECONDS)
            } finally {
                try {
                    old.stop().get(10, SECONDS)
                } finally {
                    upgraded.stop().get(10, SECONDS)
                }
            }
        }
    }
}

private class ConnectionSelectionGenerationDial(val parent: Connection, val result: CompletableFuture<Connection>, val register: () -> Unit)

private class ConnectionSelectionGenerationTransport(upgrader: ConnectionUpgrader) : TcpTransport(upgrader) {
    val start = CompletableFuture<Unit>()
    val initial = CompletableFuture<List<ConnectionSelectionGenerationDial>>()
    val later = CompletableFuture<List<ConnectionSelectionGenerationDial>>()
    val all = CopyOnWriteArrayList<ConnectionSelectionGenerationDial>()
    private val first = CopyOnWriteArrayList<ConnectionSelectionGenerationDial>()
    private val next = CopyOnWriteArrayList<ConnectionSelectionGenerationDial>()
    private val count = AtomicInteger()
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Connection> {
        val index = count.incrementAndGet()
        val result = CompletableFuture<Connection>()
        start.thenCompose {
            super.dial(addr, ConnectionHandler.create { if (index <= 3) connHandler.handleConnection(it) }, preHandler)
        }.whenComplete { parent, failure ->
            if (failure != null) {
                initial.completeExceptionally(failure)
                later.completeExceptionally(failure)
                result.completeExceptionally(failure)
            } else {
                val entry = ConnectionSelectionGenerationDial(parent, result) { connHandler.handleConnection(parent) }
                all.add(entry)
                val group = if (index <= 3) first else next
                group.add(entry)
                if (group.size == 3) (if (index <= 3) initial else later).complete(group.toList())
            }
        }
        if (index == 6) start.complete(Unit)
        return result
    }
}
