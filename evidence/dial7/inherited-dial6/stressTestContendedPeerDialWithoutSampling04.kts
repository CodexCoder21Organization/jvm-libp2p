@file:WithArtifact("foundation.url.protocol.buildMaven()")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-test:1.8.22")
@file:WithArtifact("community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-25", doNotUpgrade = true)
package foundation.url.protocol

import build.kotlin.withartifact.WithArtifact
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun stressTestContendedPeerDialWithoutSampling04() {
    val failures = ConcurrentLinkedQueue<Throwable>()
    val durations = ConcurrentLinkedQueue<Long>()
    val events = ConcurrentLinkedQueue<String>()
    val workers = Executors.newFixedThreadPool(16)
    val server = Libp2pHostFactory.createServerHost(
        listenAddresses = listOf("/ip4/127.0.0.1/tcp/0"),
        requestHandler = object : RpcRequestHandler {
            override suspend fun handleRequest(
                request: Libp2pRpcProtocol.RpcRequest,
                effectHandler: EffectPropagator,
            ): Libp2pRpcProtocol.RpcResponse {
                return Libp2pRpcProtocol.RpcResponse.success(request.id, "computed")
            }
        },
        keyPair = Libp2pHostFactory.generateKeyFromSeed("cold-peer-dial-server"),
    )
    try {
        server.start().get(10, TimeUnit.SECONDS)
        val peerId = server.peerId.toBase58()
        val addresses = server.listenAddresses().map { it.toString() }
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val active = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val stacks = ConcurrentLinkedQueue<String>()
        val tasks = (0 until 16).map { lane ->
            workers.submit {
                // Sixteen independent clients exercise genuine concurrent cold handshakes.
                repeat(16) { iteration ->
                    val provider = Libp2pNetworkProvider(
                        keySeed = "cold-peer-dial-client-$lane-$iteration",
                        listenHost = "127.0.0.1",
                        enableRelay = false,
                    )
                    val providerEntered = java.util.concurrent.atomic.AtomicLong()
                    val connectionReady = java.util.concurrent.atomic.AtomicLong()
                    val streamReady = java.util.concurrent.atomic.AtomicLong()
                    provider.addConnectionListener { event ->
                        if (event is NetworkConnectionEvent.Connected) connectionReady.set(System.nanoTime())
                    }
                    val observed = object : NetworkProvider by provider {
                        override fun dial(peerId: String, address: String, protocolId: String, timeoutMs: Long): RpcStream {
                            providerEntered.set(System.nanoTime())
                            return provider.dial(peerId, address, protocolId, timeoutMs).also { streamReady.set(System.nanoTime()) }
                        }
                    }
                    val protocol = UrlProtocol(
                        bootstrapPeers = emptyList(), network = observed,
                        relayConfig = RelayConfig(enableRelay = false),
                        lanDiscoveryConfig = LanDiscoveryConfig(enabled = false),
                    )
                    try {
                        provider.listen(0)
                        if (iteration == 0) {
                            ready.countDown()
                            assertTrue(start.await(10, TimeUnit.SECONDS), "Concurrent dial start was not released")
                        }
                        val begin = System.nanoTime()
                        active[lane] = begin
                        try {
                            protocol.openStreamToPeer(peerId, addresses, "__bytecode_request", connectionTimeoutMs = 1_000).use { stream ->
                                assertEquals(peerId, stream.remotePeerId)
                            }
                        } catch (failure: Throwable) {
                            failures.add(failure)
                            events.add("DIAL_FAILURE lane=$lane iteration=$iteration elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)} providerDelayMs=${TimeUnit.NANOSECONDS.toMillis(providerEntered.get() - begin)} connectionReadyMs=${if (connectionReady.get()==0L) -1 else TimeUnit.NANOSECONDS.toMillis(connectionReady.get()-begin)} streamReadyMs=${if (streamReady.get()==0L) -1 else TimeUnit.NANOSECONDS.toMillis(streamReady.get()-begin)}")
                            stacks.forEach { println(it) }
                            failure.printStackTrace()
                        } finally {
                            active.remove(lane)
                            durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin))
                        }
                    } finally {
                        protocol.close()
                    }
                }
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "All sixteen clients must listen before dialing")
        start.countDown()
        tasks.forEach { it.get(120, TimeUnit.SECONDS) }
        println("CONTENDED_DIAL_RESULTS attempts=${durations.size} failures=${failures.size} durationsMs=${durations.sorted()}")
        assertEquals(0, failures.size, "Every real loopback dial must complete within the unchanged 1000 ms connection budget.\n" +
            events.joinToString("\n") + "\nCauses:\n" + failures.take(2).joinToString("\n") { it.stackTraceToString() } +
            "\nSampled stacks:\n" + stacks.toList().take(2).joinToString("\n"))
    } finally {
        workers.shutdownNow()
        server.stop().get(10, TimeUnit.SECONDS)
    }
}
