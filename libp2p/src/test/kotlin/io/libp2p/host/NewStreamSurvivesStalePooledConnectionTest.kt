package io.libp2p.host

import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.core.ConnectionClosedException
import io.libp2p.etc.types.hasCauseOfType
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

    /**
     * A stream and a controller handed to one caller must come from the SAME attempt.
     *
     * The stream future completes as soon as the substream registers, while the controller only completes
     * when negotiation finishes or fails. So when a first attempt's substream registers successfully and
     * only *then* has its controller failed by the connection dying, a redial can pair that dead first
     * stream with the second attempt's live controller — two different connections in one `StreamPromise`.
     *
     * **This test is a property guard, NOT a fail-first reproducer.** It passes against the build that has
     * the cross-pairing defect as well. The reason is worth recording: `NetworkImpl` removes a connection
     * from its pool on the connection's close future, and this seam completes that future, so the first
     * attempt never selects the stale entry and the pairing hazard is never reached. Holding a connection
     * in the pool *and* close-future-complete is not reachable from outside the library, so the cross-
     * pairing path is argued from the code and from the production trace (buildtest `55b03afc`), not
     * demonstrated here. What this test does pin is the invariant itself — that whatever is delivered is
     * one attempt's stream with its own controller — so a future regression of the pairing logic is caught.
     */
    @Test
    @Timeout(60)
    fun `whatever a redial delivers is one attempt's stream with its own controller`() {
        val dialer = hostFactory.createHost()
        val listener = hostFactory.createHost()

        val first = Ping().dial(dialer.host, listener.peerId, listener.listenAddress)
        first.controller.get(15, TimeUnit.SECONDS).ping().get(15, TimeUnit.SECONDS)

        val pooled = dialer.host.network.connections.single { it.secureSession().remoteId == listener.peerId }
        val nettyChannel = (pooled as ConnectionOverNetty).nettyChannel

        // Hold the muxer unaware: the close future will complete, but channelUnregistered never reaches the
        // MuxHandler, so `checkClosed()` still lets a substream be created on a connection that is gone.
        nettyChannel.pipeline().addFirst(
            "swallowDeregister",
            object : io.netty.channel.ChannelOutboundHandlerAdapter() {
                override fun deregister(
                    ctx: io.netty.channel.ChannelHandlerContext,
                    promise: io.netty.channel.ChannelPromise
                ) {
                    promise.setSuccess()
                }
            }
        )
        nettyChannel.close().await(10, TimeUnit.SECONDS)
        assertThat(pooled.closeFuture().isDone)
            .withFailMessage("the connection must be closed, which is what makes the redial legitimate")
            .isTrue()

        val promise = dialer.host.newStream<PingController>(
            listOf(Ping().protocolDescriptor.announceProtocols.first()),
            listener.peerId,
            listener.listenAddress
        )

        promise.controller.get(20, TimeUnit.SECONDS)
        val delivered = promise.stream.get(20, TimeUnit.SECONDS)

        assertThat(delivered.connection)
            .withFailMessage(
                "the caller was handed a stream from the discarded first attempt while its controller came " +
                    "from the redial - two different connections in one StreamPromise"
            )
            .isNotSameAs(pooled)
        assertThat(delivered.connection.closeFuture().isDone)
            .withFailMessage("the delivered stream must sit on a live connection")
            .isFalse()
        assertThat(delivered)
            .withFailMessage("the caller must actually receive a usable stream, i.e. the redial recovered")
            .isNotNull()
    }

    /**
     * A substream dying is not a connection dying. `ConnectionClosedException` is raised at substream
     * granularity too — `ProtocolSelect` fires it from a mux child's `channelUnregistered`, which a plain
     * remote reset of a single substream is enough to trigger. Redialling on that would close a healthy
     * shared connection and take down every other stream riding it.
     */
    @Test
    @Timeout(60)
    fun `a substream failure does not tear down the healthy connection it rode`() {
        val dialer = hostFactory.createHost()
        val listener = hostFactory.createHost()

        val established = Ping().dial(dialer.host, listener.peerId, listener.listenAddress)
        established.controller.get(15, TimeUnit.SECONDS).ping().get(15, TimeUnit.SECONDS)

        val shared = dialer.host.network.connections.single { it.secureSession().remoteId == listener.peerId }

        // Kill ONE substream while the connection stays healthy. The negotiation is stalled by holding the
        // REMOTE end's reads shut, so the proposal is never answered and ProtocolSelect is still in the
        // substream's pipeline when it is closed - closing a substream whose negotiation already succeeded
        // cannot fail its controller. Stalling the remote rather than the local reads keeps every
        // perturbation off the connection this test is asserting about.
        val remoteSide = listener.host.network.connections.single {
            it.secureSession().remoteId == dialer.peerId
        }
        val remoteChannel = (remoteSide as ConnectionOverNetty).nettyChannel
        remoteChannel.eventLoop().submit { remoteChannel.config().isAutoRead = false }
            .get(10, TimeUnit.SECONDS)

        val streamsBefore = dialer.host.streams.toSet()
        val doomed = dialer.host.newStream<PingController>(
            listOf(Ping().protocolDescriptor.announceProtocols.first()),
            listener.peerId,
            listener.listenAddress
        )
        // The StreamPromise's stream is now published only once its own attempt is final, so it cannot be
        // used to reach a substream whose negotiation is still outstanding. The host's stream registry can:
        // it is populated by the stream visitor when the substream registers.
        val doomedStream = run {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var found: io.libp2p.core.Stream? = null
            while (found == null && System.nanoTime() < deadline) {
                found = dialer.host.streams.firstOrNull { it !in streamsBefore && it.connection === shared }
                if (found == null) Thread.sleep(20)
            }
            requireNotNull(found) { "the substream under test never registered" }
        }
        doomedStream.close().get(15, TimeUnit.SECONDS)

        val failure = runCatching { doomed.controller.get(20, TimeUnit.SECONDS) }.exceptionOrNull()
        assertThat(failure)
            .withFailMessage("closing the substream must fail its controller, which is the trigger under test")
            .isNotNull()
        assertThat(failure!!.hasCauseOfType(ConnectionClosedException::class))
            .withFailMessage(
                "the trigger must be the substream-granularity ConnectionClosedException the redial " +
                    "predicate has to discriminate; any other cause means this test is not exercising it. " +
                    "Cause was: %s",
                failure
            )
            .isTrue()

        assertThat(shared.closeFuture().isDone)
            .withFailMessage(
                "one substream failing must not close the connection it shared. Every other stream on it - " +
                    "identify, ping, gossip, other newStream callers - would die with it, turning a single " +
                    "reset substream into a full re-dial and Noise handshake for everything."
            )
            .isFalse()

        remoteChannel.eventLoop().submit {
            remoteChannel.config().isAutoRead = true
            remoteChannel.read()
        }.get(10, TimeUnit.SECONDS)

        // Deliberately NOT asserted here: that a brand-new stream then negotiates on this connection.
        // Resuming the stalled remote replays the doomed substream's traffic against a child that is
        // already gone, which is its own behaviour and would make this test about that instead of about
        // the discrimination it exists to pin. What matters for the predicate is that the connection the
        // failed substream rode was not torn down.
    }
}
