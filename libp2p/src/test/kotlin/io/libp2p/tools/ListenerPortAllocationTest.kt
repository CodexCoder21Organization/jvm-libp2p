package io.libp2p.tools

import io.libp2p.core.PlaintextTcpTest
import io.libp2p.core.PlaintextWsTest
import io.libp2p.transport.tcp.TcpTransportTest
import io.libp2p.transport.ws.WsTransportTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit.SECONDS

class ListenerPortAllocationTest {
    @Test
    fun `transport fixtures work with preheld legacy endpoints`() {
        val sockets = mutableListOf<ServerSocket>()
        try {
            for (port in listOf(21100, 21000, 21001, 21002, 21003, 21004)) {
                LegacyPortGuard.holdIfFree(port)?.let { sockets.add(it) }
            }
            `transport fixtures work while their legacy endpoints are owned`()
        } finally {
            sockets.forEach { it.close() }
        }
    }

    @Test
    fun `transport fixtures work while their legacy endpoints are owned`() {
        val sockets = listOf(21100, 21000, 21001, 21002, 21003, 21004).map { ServerSocket(it) }
        try {
            for (fixture in listOf(TcpTransportTest(), WsTransportTest())) {
                fixture.`set up transport`()
                try {
                    fixture.`dial should invoke preHandler before connection handlers`()
                } finally {
                    fixture.`close transport`()
                }
                fixture.`set up transport`()
                try {
                    fixture.`listen then unlisten`()
                } finally {
                    fixture.`close transport`()
                }
            }
        } finally {
            sockets.forEach { it.close() }
        }
    }

    @Test
    fun `host factory uses assigned addresses for independent instances`() {
        val first = HostFactory()
        val second = HostFactory()
        try {
            val firstHost = ServerSocket(first.tcpPort).use { first.createHost() }
            assertEquals(0, first.tcpPort, "Default factory listener must request OS allocation")
            assertEquals(0, second.tcpPort, "Each factory must request OS allocation")
            val hosts = listOf(firstHost, first.createHost(), second.createHost())
            assertEquals(3, hosts.map { it.listenPort }.toSet().size)
            hosts.forEach {
                assertNotEquals(0, it.listenPort)
                assertEquals(it.host.listenAddresses().single(), it.listenAddress)
            }
            val echo = Echo().dial(hosts[0].host, hosts[2].peerId, hosts[2].listenAddress)
                .controller.get(5, SECONDS)
            assertEquals("assigned address", echo.echo("assigned address").get(5, SECONDS))
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    @Test
    fun `host transport fixtures can overlap independently`() {
        val fixtures = listOf(PlaintextTcpTest(), PlaintextTcpTest(), PlaintextWsTest(), PlaintextWsTest())
        try {
            fixtures.forEach { it.startHosts() }
            fixtures.forEach { it.echoOverSecureConnection() }
            assertEquals(4, fixtures.map { it.serverHost.listenAddresses().single() }.toSet().size)
        } finally {
            fixtures.forEach { it.stopHosts() }
        }
    }
}
