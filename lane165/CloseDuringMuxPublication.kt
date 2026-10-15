package lane165

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

fun main() {
    val client = host {
        identity { random() }
        transports { +::TcpTransport }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { +StreamMuxerProtocol.getYamux() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { +Ping() }
    }
    val server = host {
        identity { random() }
        transports { +::TcpTransport }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { +StreamMuxerProtocol.getYamux() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { +Ping() }
    }
    val published = CompletableFuture<Connection>()
    client.addConnectionHandler(ConnectionHandler.create { connection ->
        connection.close()
        published.complete(connection)
    })
    var primary: Throwable? = null
    val cleanupFailures = mutableListOf<Throwable>()
    try {
        server.start().get(5, TimeUnit.SECONDS)
        client.start().get(5, TimeUnit.SECONDS)
        client.network.connect(server.peerId, *server.listenAddresses().toTypedArray())
            .get(5, TimeUnit.SECONDS)
        val connection = published.get(5, TimeUnit.SECONDS)
        connection.closeFuture().get(5, TimeUnit.SECONDS)
        val promise = client.newStream<PingController>(Ping().protocolDescriptor.announceProtocols, connection)
        println("ROOT_CLOSED=${connection.closeFuture().isDone} STREAM_DONE=${promise.stream.isDone} CONTROLLER_DONE=${promise.controller.isDone}")
        val failure = assertFailsWith<ExecutionException>("A stream opened on the closed parent must report connection closure") {
            promise.controller.get(5, TimeUnit.SECONDS)
        }
        assertTrue(failure.cause is io.libp2p.core.ConnectionClosedException,
            "The controller must report ConnectionClosedException, but was ${failure.cause}")
        assertFailsWith<ExecutionException>("The stream future must report the same closed parent") {
            promise.stream.get(5, TimeUnit.SECONDS)
        }
        println("CLOSED_PARENT_FUTURES_PASS")
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        for (node in listOf(client, server)) {
            try { node.stop().get(5, TimeUnit.SECONDS) } catch (failure: Throwable) { cleanupFailures += failure }
        }
        if (primary != null) cleanupFailures.forEach { primary.addSuppressed(it) }
        else if (cleanupFailures.isNotEmpty()) {
            val failure = cleanupFailures.first()
            cleanupFailures.drop(1).forEach { failure.addSuppressed(it) }
            throw failure
        }
    }
}
