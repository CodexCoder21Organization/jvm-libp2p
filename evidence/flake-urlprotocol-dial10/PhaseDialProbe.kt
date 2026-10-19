import foundation.url.protocol.*
import foundation.url.protocol.sandbox.BytecodeProtocol
import foundation.url.protocol.sandbox.BytecodeFetchResult
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun main(args: Array<String>) {

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
    repeat(2) { ordinal ->
    providerEntered.set(0); connected.set(0); streamReady.set(0); handlerEntered.set(0)
    val dialEvent = DialInterval().apply { this.ordinal=ordinal }
    val handshakeEvent = DialInterval().apply { this.ordinal=ordinal; phase="provider-to-connection" }
    val provider = Libp2pNetworkProvider(keySeed="ILOG1b-remote-retirement-client", listenHost="127.0.0.1", enableRelay=false)
    val observed = object : NetworkProvider by provider {
        override fun dial(peerId: String, address: String, protocolId: String, timeoutMs: Long): RpcStream {
            providerEntered.set(System.nanoTime())
            handshakeEvent.begin()
            return provider.dial(peerId, address, protocolId, timeoutMs).also { streamReady.set(System.nanoTime()) }
        }
    }
    val protocol = UrlProtocol(bootstrapPeers=emptyList(),network=observed,
        relayConfig=RelayConfig(enableRelay=false),lanDiscoveryConfig=LanDiscoveryConfig(enabled=false))
    try {
        server.start().get(10,TimeUnit.SECONDS)
        provider.listen(0)
        provider.addConnectionListener { if (it is NetworkConnectionEvent.Connected) connected.compareAndSet(0,System.nanoTime()).also { if(it) { handshakeEvent.end(); handshakeEvent.commit() } } }
        val peer=Libp2pPeer.remote(server.peerId.toBase58(),server.listenAddresses().map { it.toString() })
        try {
            dialEvent.begin()
            begin.set(System.nanoTime())
            val result=protocol.requestBytecodeConditionallyFromPeer(peer,"ilog1b.remote.bytecode",null,null,
                requestTimeoutMs=15_000,connectionTimeoutMs=1_000)
            check(result is BytecodeFetchResult.Downloaded)
            assertContentEquals("computed".toByteArray(),result.response.jarBytes)
    
            println("DIAL_PASS ordinal=$ordinal providerMs=${TimeUnit.NANOSECONDS.toMillis(providerEntered.get()-begin.get())} connectionMs=${TimeUnit.NANOSECONDS.toMillis(connected.get()-begin.get())} streamMs=${TimeUnit.NANOSECONDS.toMillis(streamReady.get()-begin.get())} handlerMs=${TimeUnit.NANOSECONDS.toMillis(handlerEntered.get()-begin.get())}")
        } catch (failure: Throwable) {
            println("DIAL_FAIL ordinal=$ordinal providerMs=${TimeUnit.NANOSECONDS.toMillis(providerEntered.get()-begin.get())} connectionMs=${TimeUnit.NANOSECONDS.toMillis(connected.get()-begin.get())} streamMs=${TimeUnit.NANOSECONDS.toMillis(streamReady.get()-begin.get())} handlerMs=${TimeUnit.NANOSECONDS.toMillis(handlerEntered.get()-begin.get())}")
    
            throw failure
        } finally { dialEvent.end(); dialEvent.commit() }
    } finally {
        protocol.close()
        if (ordinal==1) server.stop().get(10,TimeUnit.SECONDS)
    }
}
}

@jdk.jfr.Name("dial9.Interval")
@jdk.jfr.Label("Public dial phase")
@jdk.jfr.StackTrace(false)
class DialInterval : jdk.jfr.Event() {
    @JvmField var ordinal: Int = 0
    @JvmField var phase: String = "public-dial"
}
