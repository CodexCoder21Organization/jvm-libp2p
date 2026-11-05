package io.libp2p.transport

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.SocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean

class TransportAddressConversionCloseTest {
    @Test
    fun `reading listener addresses allows conversion to close from another thread`() {
        val closeDuringConversion = AtomicBoolean(false)
        val transport = object : TcpTransport(NullConnectionUpgrader()) {
            override fun toMultiaddr(addr: SocketAddress): Multiaddr {
                if (closeDuringConversion.compareAndSet(true, false)) {
                    CompletableFuture.runAsync { close().get(5, SECONDS) }.get(5, SECONDS)
                }
                return super.toMultiaddr(addr)
            }
        }
        try {
            transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { }).get(5, SECONDS)
            val address = transport.listenAddresses().single()
            closeDuringConversion.set(true)
            assertEquals(listOf(address), transport.listenAddresses())
            assertEquals(0, transport.activeListeners)
            assertEquals(emptyList<Multiaddr>(), transport.listenAddresses())
        } finally {
            transport.close().get(5, SECONDS)
        }
    }

    @Test
    fun `unlistening by actual address allows conversion to close from another thread`() {
        val closeDuringConversion = AtomicBoolean(false)
        val transport = object : TcpTransport(NullConnectionUpgrader()) {
            override fun toMultiaddr(addr: SocketAddress): Multiaddr {
                if (closeDuringConversion.compareAndSet(true, false)) {
                    CompletableFuture.runAsync { close().get(5, SECONDS) }.get(5, SECONDS)
                }
                return super.toMultiaddr(addr)
            }
        }
        try {
            transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { }).get(5, SECONDS)
            val address = transport.listenAddresses().single()
            closeDuringConversion.set(true)
            transport.unlisten(address).get(5, SECONDS)
            assertEquals(0, transport.activeListeners)
            assertEquals(emptyList<Multiaddr>(), transport.listenAddresses())
        } finally {
            transport.close().get(5, SECONDS)
        }
    }
}
