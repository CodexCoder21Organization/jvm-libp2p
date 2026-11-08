@file:WithArtifact("community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-46")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-test:1.9.22")
package foundation.url.resolver

import build.kotlin.withartifact.WithArtifact
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

fun testExplicitParentCloseClosesItsAcceptedChild() {
    val pendingController = CompletableFuture<Unit>()
    val provisionalProtocol = object : StrictProtocolBinding<Unit>(
        "/test/parent-close/provisional/1.0.0",
        object : ProtocolHandler<Unit>(1, 1) {
            override fun onStartInitiator(stream: Stream) = pendingController
            override fun onStartResponder(stream: Stream) = CompletableFuture.completedFuture(Unit)
        },
    ) {}
    val client = host {
        secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
        muxers { +StreamMuxerProtocol.getYamux() }
        protocols { +Ping(); +provisionalProtocol }
    }
    val server = host {
        secureChannels { add { key, _ -> NoiseXXSecureChannel(key) } }
        muxers { +StreamMuxerProtocol.getYamux() }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        protocols { +Ping(); +provisionalProtocol }
    }
    try {
        client.start().get(10, SECONDS)
        server.start().get(10, SECONDS)
        val parent = client.network.connect(server.peerId, server.listenAddresses().single()).get(10, SECONDS)
        val accepted = client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), parent)
        val ping = accepted.controller.get(5, SECONDS)
        ping.ping().get(5, SECONDS)
        val acceptedChild = accepted.stream.get(5, SECONDS)
        val provisional = client.newStream<Unit>(listOf("/test/parent-close/provisional/1.0.0"), parent)
        val provisionalChild = provisional.stream.get(5, SECONDS)
        assertTrue(provisional.controller.cancel(false), "The provisional controller must still be pending when cancelled")
        provisionalChild.close().get(5, SECONDS)
        assertFalse(acceptedChild.closeFuture().isDone, "Cancelling the provisional child must leave its accepted sibling open")
        assertTrue(client.network.reserveConnection(parent).get(5, SECONDS), "The live parent must remain eligible after provisional cancellation")
        parent.close().get(5, SECONDS)
        acceptedChild.closeFuture().get(5, SECONDS)
        assertTrue(acceptedChild.closeFuture().isDone, "Explicit parent close also closes an accepted child despite its successful reservation")
    } finally {
        pendingController.cancel(false)
        try { client.stop().get(10, SECONDS) } finally { server.stop().get(10, SECONDS) }
    }
}
