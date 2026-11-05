package io.libp2p.transport

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.tcp.TcpTransport
import io.libp2p.transport.ws.WsTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit.SECONDS

class EphemeralTransportListenersTest {
    @Test
    fun `five ephemeral listeners remain owned and can be unlistened by actual address`() {
        for (transport in listOf(TcpTransport(NullConnectionUpgrader()), WsTransport(NullConnectionUpgrader()))) {
            try {
                val suffix = if (transport is WsTransport) "/ws" else ""
                repeat(5) {
                    transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0$suffix"), { }).get(5, SECONDS)
                }
                val addresses = transport.listenAddresses()
                assertEquals(5, transport.activeListeners)
                assertEquals(5, addresses.toSet().size)
                addresses.forEach { address ->
                    assertNotEquals("0", address.getFirstComponent(io.libp2p.core.multiformats.Protocol.TCP)?.stringValue)
                    transport.unlisten(address).get(5, SECONDS)
                }
                assertEquals(0, transport.activeListeners)
            } finally {
                transport.close().get(5, SECONDS)
            }
        }
    }

    @Test
    fun `unlisten requested port zero removes the oldest listener only`() {
        val transport = TcpTransport(NullConnectionUpgrader())
        val requested = Multiaddr("/ip4/127.0.0.1/tcp/0")
        try {
            repeat(3) { transport.listen(requested, { }).get(5, SECONDS) }
            val addresses = transport.listenAddresses()
            assertEquals(3, addresses.size)
            transport.unlisten(requested).get(5, SECONDS)
            assertEquals(addresses.drop(1), transport.listenAddresses())
            assertEquals(2, transport.activeListeners)
            transport.close().get(5, SECONDS)
            assertEquals(0, transport.activeListeners)
            addresses.forEach { address ->
                val port = address.getFirstComponent(io.libp2p.core.multiformats.Protocol.TCP)!!.stringValue!!.toInt()
                ServerSocket(port).use { assertEquals(port, it.localPort) }
            }
        } finally {
            transport.close().get(5, SECONDS)
        }
    }
}
