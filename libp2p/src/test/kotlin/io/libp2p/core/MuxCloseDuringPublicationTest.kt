package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class MuxCloseDuringPublicationTest {
    @Test
    fun yamuxOpenAfterCloseDuringPublicationReportsClosure() {
        closeDuringMuxPublication(StreamMuxerProtocol.getYamux(), false)
    }

    @Test
    fun mplexOpenAfterCloseDuringPublicationReportsClosure() {
        closeDuringMuxPublication(StreamMuxerProtocol.Mplex, false)
    }

    @Test
    fun yamuxPendingOpenFailsWhenPublicationClosesItsParent() {
        closeDuringMuxPublication(StreamMuxerProtocol.getYamux(), true)
    }

    @Test
    fun mplexPendingOpenFailsWhenPublicationClosesItsParent() {
        closeDuringMuxPublication(StreamMuxerProtocol.Mplex, true)
    }
}

private fun closeDuringMuxPublication(muxer: StreamMuxerProtocol, openBeforeClose: Boolean) {
    val client = host {
        identity { random() }
        transports { +::TcpTransport }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { +muxer }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { +Ping() }
    }
    val server = host {
        identity { random() }
        transports { +::TcpTransport }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { +muxer }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { +Ping() }
    }
    val published = CompletableFuture<Pair<Connection, StreamPromise<PingController>?>>()
    client.addConnectionHandler(ConnectionHandler.create { connection ->
        val pending = if (openBeforeClose) {
            client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols, connection)
        } else null
        connection.close()
        published.complete(connection to pending)
    })
    var primary: Throwable? = null
    try {
        server.start().get(5, TimeUnit.SECONDS)
        client.start().get(5, TimeUnit.SECONDS)
        client.network.connect(server.peerId, *server.listenAddresses().toTypedArray()).get(5, TimeUnit.SECONDS)
        val (connection, pending) = published.get(5, TimeUnit.SECONDS)
        connection.closeFuture().get(5, TimeUnit.SECONDS)
        val promise = pending ?: client.newStream<PingController>(
            Ping().protocolDescriptor.announceProtocols, connection
        )
        val controllerFailure = assertThrows(ExecutionException::class.java) {
            promise.controller.get(5, TimeUnit.SECONDS)
        }
        assertEquals(ConnectionClosedException::class.java, controllerFailure.cause!!.javaClass)
        val streamFailure = assertThrows(ExecutionException::class.java) {
            promise.stream.get(5, TimeUnit.SECONDS)
        }
        assertEquals(ConnectionClosedException::class.java, streamFailure.cause!!.javaClass)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        val failures = mutableListOf<Throwable>()
        for (node in listOf(client, server)) {
            try { node.stop().get(5, TimeUnit.SECONDS) } catch (failure: Throwable) { failures += failure }
        }
        if (primary != null) failures.forEach { primary.addSuppressed(it) }
        else if (failures.isNotEmpty()) {
            val failure = failures.first()
            failures.drop(1).forEach { failure.addSuppressed(it) }
            throw failure
        }
    }
}
