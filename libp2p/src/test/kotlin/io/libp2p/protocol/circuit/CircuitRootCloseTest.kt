package io.libp2p.protocol.circuit

import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CircuitRootCloseTest {
    @Test
    fun closingEitherCircuitEndpointClosesTheOtherRootAndItsChildren() {
        val hosts = mutableListOf<Host>()
        val runners = mutableListOf<ScheduledThreadPoolExecutor>()
        val children = mutableListOf<Stream>()
        val accepted = LinkedBlockingQueue<Stream>()
        val protocol = "/test/circuit-root-close/1.0.0"
        var primaryFailure: Throwable? = null
        fun build(candidates: List<RelayTransport.CandidateRelay>, listenCircuit: String? = null): Host {
            val key = generateKeyPair(KeyType.ED25519).first
            val stop = CircuitStopProtocol.Binding(CircuitStopProtocol())
            val hop = CircuitHopProtocol.Binding(
                CircuitHopProtocol.RelayManager.limitTo(key, PeerId.fromPubKey(key.publicKey()), 5), stop
            )
            val runner = ScheduledThreadPoolExecutor(1).also { runners.add(it) }
            val transport = AtomicReference<RelayTransport>()
            val result = host {
                identity { factory = { key } }
                transports {
                    add(::TcpTransport)
                    add { upgrader ->
                        RelayTransport(hop, stop, upgrader, { candidates }, runner).also { transport.set(it) }
                    }
                }
                secureChannels { add(::NoiseXXSecureChannel) }
                muxers { +StreamMuxerProtocol.getYamux() }
                network {
                    listen("/ip4/127.0.0.1/tcp/0")
                    if (listenCircuit != null) listen(listenCircuit)
                }
                protocols {
                    +hop
                    +stop
                    +object : StrictProtocolBinding<Unit>(protocol, object : P2PChannelHandler<Unit> {
                        override fun initChannel(ch: P2PChannel): CompletableFuture<Unit> {
                            val stream = ch as Stream
                            if (!stream.isInitiator) accepted.offer(stream)
                            return CompletableFuture.completedFuture(Unit)
                        }
                    }) {}
                }
            }
            transport.get().setHost(result)
            hosts.add(result)
            return result
        }
        fun awaitClosed(future: CompletableFuture<*>, message: String) {
            try {
                future.get(10, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                assertTrue(future.isDone, message)
            }
        }
        try {
            val relay = build(emptyList())
            relay.start().get(10, TimeUnit.SECONDS)
            val relayAddress = relay.listenAddresses().first()
            val candidates = listOf(RelayTransport.CandidateRelay(relay.peerId, relay.listenAddresses()))
            val server = build(candidates, "$relayAddress/p2p-circuit")
            val client = build(candidates)
            server.start().get(10, TimeUnit.SECONDS)
            client.start().get(10, TimeUnit.SECONDS)
            val target = relayAddress.concatenated(Multiaddr("/p2p-circuit/p2p/${server.peerId.toBase58()}"))
            for (closeClient in listOf(true, false)) {
                val root = client.network.transports.single { it is RelayTransport }
                    .dial(target, client.network.connectionHandler, null).get(10, TimeUnit.SECONDS)
                val promise = client.newStream<Unit>(listOf(protocol), root)
                val child = promise.stream.get(10, TimeUnit.SECONDS).also { children.add(it) }
                promise.controller.get(10, TimeUnit.SECONDS)
                val remoteChild = accepted.poll(10, TimeUnit.SECONDS)
                    ?: throw AssertionError("The real circuit endpoint must accept the child")
                children.add(remoteChild)
                val remoteRoot = remoteChild.connection
                assertTrue(root.transport() is RelayTransport)
                assertTrue(remoteRoot.transport() is RelayTransport)
                assertEquals(client.peerId, remoteRoot.secureSession().remoteId)
                if (closeClient) root.close().get(10, TimeUnit.SECONDS)
                else remoteRoot.close().get(10, TimeUnit.SECONDS)
                awaitClosed(if (closeClient) remoteRoot.closeFuture() else root.closeFuture(),
                    "Closing one circuit root must close its paired root through the relay")
                awaitClosed(child.closeFuture(), "The client child must close with its circuit root")
                awaitClosed(remoteChild.closeFuture(), "The server child must close with its circuit root")
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val owner = primaryFailure ?: cleanupFailure
                    if (owner == null) cleanupFailure = failure else if (owner !== failure) owner.addSuppressed(failure)
                }
            }
            children.forEach { stream -> cleanup { stream.close().get(10, TimeUnit.SECONDS) } }
            hosts.asReversed().forEach { peer -> cleanup { peer.stop().get(10, TimeUnit.SECONDS) } }
            runners.forEach { runner -> cleanup {
                runner.shutdownNow()
                assertTrue(runner.awaitTermination(5, TimeUnit.SECONDS), "Relay maintenance must stop")
            } }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }
}
