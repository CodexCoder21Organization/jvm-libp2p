package io.libp2p.network

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.newStream
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.SECONDS

class NetworkProvisionalCloseTest {
    @Test
    fun parentCloseStaysTerminalWhenChildInitializationCompletesLater() {
        val entered = CompletableFuture<Unit>()
        val initialization = CompletableFuture<Unit>()
        val binding = object : StrictProtocolBinding<Unit>(
            "/test/provisional-close/1.0.0",
            object : ProtocolHandler<Unit>(1, 1) {
                override fun onStartInitiator(stream: Stream): CompletableFuture<Unit> {
                    entered.complete(Unit)
                    return initialization
                }
                override fun onStartResponder(stream: Stream) = CompletableFuture.completedFuture(Unit)
            }
        ) {}
        val server = host {
            network { listen("/ip4/127.0.0.1/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(binding) }
        }
        val client = host {
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            protocols { add(binding) }
        }
        try {
            server.start().get(10, SECONDS)
            client.start().get(10, SECONDS)
            val parent = client.network.connect(server.peerId, server.listenAddresses().single()).get(10, SECONDS)
            val hold = checkNotNull(client.network.holdConnection(parent).get(5, SECONDS))
            val child = client.newStream<Unit>(listOf("/test/provisional-close/1.0.0"), hold)
            entered.get(5, SECONDS)
            assertFalse(child.controller.isDone, "Child initialization must still be pending")
            parent.close().get(5, SECONDS)
            val failure = assertThrows(ExecutionException::class.java) { child.controller.get(5, SECONDS) }
            assertTrue(failure.cause is ConnectionClosedException)
            initialization.complete(Unit)
            assertTrue(child.controller.isCompletedExceptionally, "Late initialization cannot restore the closed child")
            assertFalse(hold.promote(), "A closed parent cannot be promoted")
            val later = client.newStream<Unit>(listOf("/test/provisional-close/1.0.0"), hold)
            val laterFailure = assertThrows(ExecutionException::class.java) { later.controller.get(5, SECONDS) }
            assertEquals(
                "Cannot open a provisional stream because its hold was released or its connection is closed or excluded from selection: $parent",
                laterFailure.cause?.message
            )
            assertNull(client.network.holdConnection(parent).get(5, SECONDS))
            hold.close()
            hold.close()
        } finally {
            initialization.complete(Unit)
            try {
                client.stop().get(10, SECONDS)
            } finally {
                server.stop().get(10, SECONDS)
            }
        }
    }
}
