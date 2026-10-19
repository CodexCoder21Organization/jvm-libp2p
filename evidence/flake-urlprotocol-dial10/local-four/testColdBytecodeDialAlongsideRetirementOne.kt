package foundation.url.protocol
import foundation.url.protocol.*
import foundation.url.protocol.sandbox.BytecodeProtocol
import foundation.url.protocol.sandbox.BytecodeFetchResult
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun testColdBytecodeDialAlongsideRetirementOne() {
    val samples = ConcurrentLinkedQueue<String>()
    val sampler = Executors.newSingleThreadScheduledExecutor()
    val providerEntered = AtomicLong()
    val connected = AtomicLong()
    val streamReady = AtomicLong()
    val handlerEntered = AtomicLong()
    val begin = AtomicLong()
    val server = Libp2pHostFactory.createServerHost(
        listenAddresses = listOf("/ip4/127.0.0.1/tcp/0"),
        requestHandler = object : RpcRequestHandler {
            override suspend fun handleRequest(request: Libp2pRpcProtocol.RpcRequest, effectHandler: EffectPropagator): Libp2pRpcProtocol.RpcResponse {
                handlerEntered.set(System.nanoTime())
                return Libp2pRpcProtocol.RpcResponse.success(request.id,
                    BytecodeProtocol.createBinaryResponse("computed".toByteArray(), "example.ColdDial"))
            }
        },
        keyPair = Libp2pHostFactory.generateKeyFromSeed("ILOG1b-remote-retirement-server"),
        idleTimeoutSeconds = 120,
    )
    val provider = Libp2pNetworkProvider(keySeed="ILOG1b-remote-retirement-client", listenHost="127.0.0.1", enableRelay=false)
    val observed = object : NetworkProvider by provider {
        override fun dial(peerId: String, address: String, protocolId: String, timeoutMs: Long): RpcStream {
            providerEntered.set(System.nanoTime())
            return provider.dial(peerId, address, protocolId, timeoutMs).also { streamReady.set(System.nanoTime()) }
        }
    }
    val protocol = UrlProtocol(bootstrapPeers=emptyList(),network=observed,
        relayConfig=RelayConfig(enableRelay=false),lanDiscoveryConfig=LanDiscoveryConfig(enabled=false))
    try {
        server.start().get(10,TimeUnit.SECONDS)
        provider.listen(0)
        provider.addConnectionListener { if (it is NetworkConnectionEvent.Connected) connected.compareAndSet(0,System.nanoTime()) }
        val peer=Libp2pPeer.remote(server.peerId.toBase58(),server.listenAddresses().map { it.toString() })
        val probe=sampler.scheduleAtFixedRate({
            val start=begin.get()
            if (start!=0L && System.nanoTime()-start >= TimeUnit.MILLISECONDS.toNanos(400)) {
                samples.add("SAMPLE at ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)}ms\n"+
                    Thread.getAllStackTraces().entries.filter { (t,stack) ->
                        t.name.contains("libp2p-nio") || t.name.contains("noise") || t.name.contains("peer-dial") ||
                            stack.any { it.className.contains("io.libp2p") }
                    }.joinToString("\n") { (t,stack) -> "${t.name} ${t.state}\n"+stack.joinToString("\n") { "  at $it" } })
            }
        },100,100,TimeUnit.MILLISECONDS)
        try {
            begin.set(System.nanoTime())
            val result=protocol.requestBytecodeConditionallyFromPeer(peer,"ilog1b.remote.bytecode",null,null,
                requestTimeoutMs=15_000,connectionTimeoutMs=1_000)
            check(result is BytecodeFetchResult.Downloaded)
            assertContentEquals("computed".toByteArray(),result.response.jarBytes)
            samples.forEach { println(it) }
            println("DIAL_PASS providerMs=${TimeUnit.NANOSECONDS.toMillis(providerEntered.get()-begin.get())} connectionMs=${TimeUnit.NANOSECONDS.toMillis(connected.get()-begin.get())} streamMs=${TimeUnit.NANOSECONDS.toMillis(streamReady.get()-begin.get())} handlerMs=${TimeUnit.NANOSECONDS.toMillis(handlerEntered.get()-begin.get())}")
        } catch (failure: Throwable) {
            println("DIAL_FAIL providerMs=${TimeUnit.NANOSECONDS.toMillis(providerEntered.get()-begin.get())} connectionMs=${TimeUnit.NANOSECONDS.toMillis(connected.get()-begin.get())} streamMs=${TimeUnit.NANOSECONDS.toMillis(streamReady.get()-begin.get())} handlerMs=${TimeUnit.NANOSECONDS.toMillis(handlerEntered.get()-begin.get())}")
            samples.forEach { println(it) }
            throw AssertionError("Cold bytecode dial failed.\n" + samples.joinToString("\n") + "\n" + failure.stackTraceToString(), failure)
        } finally { probe.cancel(false) }
    } finally {
        sampler.shutdownNow()
        protocol.close()
        server.stop().get(10,TimeUnit.SECONDS)
    }
}
