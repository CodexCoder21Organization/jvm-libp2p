package io.libp2p.protocol

import identify.pb.IdentifyOuterClass
import io.libp2p.core.Host
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.types.toProtobuf
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.tools.HostFactory
import io.libp2p.transport.tcp.TcpTransport
import io.libp2p.transport.ws.WsTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.SECONDS

class IdentifyBoundAddressTest {
    @Test
    fun `default identify advertises actual TCP and WebSocket listeners`() {
        for (ws in listOf(false, true)) {
            val server = identifyAddressHost(ws, Identify(), true)
            val client = identifyAddressHost(ws, Identify(), false)
            try {
                server.start().get(5, SECONDS)
                client.start().get(5, SECONDS)
                assertEquals(actualIdentifyAddresses(server), identifyAddresses(client, server))
            } finally {
                client.stop().get(5, SECONDS)
                server.stop().get(5, SECONDS)
            }
        }
    }

    @Test
    fun `default identify follows listener changes on an existing connection`() {
        val server = identifyAddressHost(false, Identify(), true)
        val client = identifyAddressHost(false, Identify(), false)
        try {
            server.start().get(5, SECONDS)
            client.start().get(5, SECONDS)
            val target = server.listenAddresses().single()
            assertEquals(actualIdentifyAddresses(server), identifyAddresses(client, server, target))
            server.network.unlisten(server.network.transports.single().listenAddresses().single()).get(5, SECONDS)
            assertEquals(emptyList<String>(), identifyAddresses(client, server, target))
            server.network.listen(Multiaddr("/ip4/127.0.0.1/tcp/0")).get(5, SECONDS)
            assertEquals(actualIdentifyAddresses(server), identifyAddresses(client, server, target))
        } finally {
            client.stop().get(5, SECONDS)
            server.stop().get(5, SECONDS)
        }
    }

    @Test
    fun `explicit identify messages before or after build remain unchanged`() {
        val message = IdentifyOuterClass.Identify.newBuilder()
            .setAgentVersion("custom-agent")
            .addListenAddrs(Multiaddr("/dns4/public.example/tcp/1234").serialize().toProtobuf())
            .build()
        for (beforeBuild in listOf(false, true)) {
            val binding = if (beforeBuild) Identify(message) else Identify()
            val server = identifyAddressHost(false, binding, true)
            val client = identifyAddressHost(false, Identify(), false)
            if (!beforeBuild) binding.protocol.idMessage = message
            try {
                server.start().get(5, SECONDS)
                client.start().get(5, SECONDS)
                val received = Identify().dial(client, server.peerId, server.listenAddresses().single())
                    .controller.get(5, SECONDS).id().get(5, SECONDS)
                assertEquals(message, received.toBuilder().clearObservedAddr().build())
            } finally {
                client.stop().get(5, SECONDS)
                server.stop().get(5, SECONDS)
            }
        }
    }

    @Test
    fun `factory hosts advertise their own listeners independently`() {
        val factory = HostFactory()
        try {
            val first = factory.createHost()
            val second = factory.createHost()
            val client = factory.createHost()
            for (server in listOf(first, second)) {
                val message = Identify().dial(client.host, server.peerId, server.listenAddress)
                    .controller.get(5, SECONDS).id().get(5, SECONDS)
                assertEquals(server.pubKey.bytes().toProtobuf(), message.publicKey)
                assertEquals(
                    actualIdentifyAddresses(server.host),
                    message.listenAddrsList.map { Multiaddr.deserialize(it.toByteArray()).toString() }
                )
            }
        } finally {
            factory.shutdown()
        }
    }
}

private fun identifyAddressHost(ws: Boolean, binding: Identify, listen: Boolean): Host = host {
    transports { if (ws) add(::WsTransport) else add(::TcpTransport) }
    secureChannels { add(::PlaintextInsecureChannel) }
    muxers { +StreamMuxerProtocol.Mplex }
    protocols { +binding }
    if (listen) network { listen("/ip4/127.0.0.1/tcp/0" + if (ws) "/ws" else "") }
}

private fun actualIdentifyAddresses(server: Host): List<String> =
    server.network.transports.flatMap { it.listenAddresses() }.map { it.toString() }

private fun identifyAddresses(
    client: Host,
    server: Host,
    target: Multiaddr = server.listenAddresses().single()
): List<String> = Identify().dial(client, server.peerId, target).controller.get(5, SECONDS)
    .id().get(5, SECONDS).listenAddrsList.map { Multiaddr.deserialize(it.toByteArray()).toString() }
