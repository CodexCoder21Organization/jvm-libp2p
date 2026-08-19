package io.libp2p.host

import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.tools.HostFactory
import io.libp2p.transport.implementation.ConnectionOverNetty
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * `Host.newStream(protocols, peer, addr)` owns BOTH the connection selection and the stream creation, so a
 * caller must never be handed a failure caused by that selection. When the connection this API picks out of
 * its own pool turns out to be unusable, the caller's stream is its problem to solve, not the caller's.
 *
 * Why this is not hypothetical: `NetworkImpl.connect` short-circuits on a pooled connection whose
 * `closeFuture().isDone` is false, while `AbstractMuxHandler.checkClosed()` — the thing that actually
 * refuses stream creation — gates on its own `closed` flag, set in `channelUnregistered`. Those are
 * different signals, and the gap between selection and use cannot be closed by any check, because the
 * connection can die in it.
 *
 * Measured in UrlResolver buildtest run `dfe616d9`, three independent firings in one run, identical shape:
 * ```
 * connsBeforeDial=[total=1 toPeer=1 toPeerClosed=0]   <- alive pooled connection at selection time
 * connLifecycle=[never-established]                   <- so connect short-circuited; no new dial
 * connsAtOutcome=[total=0]                            <- gone by the time the failure was reported
 * outcome=ConnectionClosedException("Can't create a new stream: connection was closed")
 * totalWaitMs=100..115
 * ```
 * A reachable peer reported unreachable in ~100 ms, purely because the pool handed out a corpse.
 *
 * The seam used here forces the exact state deterministically instead of racing for it: the pooled
 * connection's muxer is driven to its closed state via `channelUnregistered` while the channel itself stays
 * open, so `closeFuture().isDone` is still false. That is precisely the state the selection gate cannot see
 * and the muxer refuses — no sleeps, no iteration counts, no dependence on machine speed.
 */
class NewStreamSurvivesStalePooledConnectionTest {

    private val hostFactory = HostFactory()

    @AfterEach
    fun cleanup() {
        hostFactory.shutdown()
    }

    @Test
    @Timeout(60)
    fun `newStream recovers when its own pool hands it an unusable connection`() {
        val dialer = hostFactory.createHost()
        val listener = hostFactory.createHost()

        // Establish a real connection so the dialer's pool holds one for this peer.
        val first = Ping().dial(dialer.host, listener.peerId, listener.listenAddress)
        first.controller.get(15, TimeUnit.SECONDS).ping().get(15, TimeUnit.SECONDS)

        val pooled = dialer.host.network.connections.single {
            it.secureSession().remoteId == listener.peerId
        }
        assertThat(pooled.closeFuture().isDone)
            .withFailMessage("the pooled connection must still look alive to the selection gate")
            .isFalse()

        // Force the exact production state: the muxer has seen channelUnregistered and will refuse new
        // streams, while the channel is still open so `closeFuture().isDone` remains false and the
        // selection gate still accepts this connection.
        val nettyChannel = (pooled as ConnectionOverNetty).nettyChannel
        nettyChannel.eventLoop().submit { nettyChannel.pipeline().fireChannelUnregistered() }
            .get(10, TimeUnit.SECONDS)
        assertThat(pooled.closeFuture().isDone)
            .withFailMessage("the seam must not close the channel; the point is that the gate cannot tell")
            .isFalse()

        // The contract under test. The caller asked for a stream to a reachable peer at a good address.
        val second = dialer.host.newStream<PingController>(
            listOf(Ping().protocolDescriptor.announceProtocols.first()),
            listener.peerId,
            listener.listenAddress
        )

        val controller = try {
            second.controller.get(20, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            throw AssertionError(
                "newStream failed its caller with a connection it chose itself out of its own pool. The " +
                    "peer is reachable and the address is good; the only thing wrong was the pooled " +
                    "connection, which this API selected. It must drop that entry and dial rather than " +
                    "hand the caller the muxer's refusal. Cause: $failure",
                failure
            )
        }

        assertThat(controller)
            .withFailMessage("a recovered attempt must yield a usable controller")
            .isNotNull()

        // The recovered stream must be a real one on a live connection - not a controller handed back over
        // the corpse. Deliberately not exercising a ping round-trip here: the host's own Ping binding owns a
        // scheduler that the evicted connection's teardown can shut down, and that is a property of this
        // fixture rather than of the contract under test.
        val recovered = second.stream.get(15, TimeUnit.SECONDS)
        assertThat(recovered.connection.closeFuture().isDone)
            .withFailMessage("the recovered stream must sit on a live connection, not the evicted one")
            .isFalse()
        assertThat(recovered.connection)
            .withFailMessage("the evicted connection must not have been handed back")
            .isNotSameAs(pooled)
    }
}
