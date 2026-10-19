package foundation.url.protocol

import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.channel.Channel
import io.netty.channel.ServerChannel
import io.netty.util.AttributeKey
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.algebraiceffects.Effective

fun testBytecodeRemoteRetirementNamesLocalLinkIdentity() {
    runBytecodeRemoteRetirementNamesLocalLinkIdentity()
}

private fun runBytecodeRemoteRetirementNamesLocalLinkIdentity() {
    fun rootConnectionChannel(channel: Channel): Channel {
        var current = channel
        while (true) {
            val parent = current.parent() ?: return current
            if (parent is ServerChannel) return current
            current = parent
        }
    }

    val service = "ilog1b.remote.bytecode"
    val expectedOutboxMessage =
        "The request handler completed, but its response could not be returned because the awaited " +
            "response outbox was full. The request was not replayed."
    val handlerEntered = CountDownLatch(1)
    val releaseHandler = CountDownLatch(1)
    val handlerReturned = CountDownLatch(1)
    val responseAdmitted = CountDownLatch(1)
    val outboxEvents = ConcurrentLinkedQueue<String>()
    val closed = java.util.concurrent.CompletableFuture<ConnectionClosedEffect>()

    val serverHost = Libp2pHostFactory.createServerHost(
        listenAddresses = listOf("/ip4/127.0.0.1/tcp/0"),
        requestHandler = object : RpcRequestHandler {
            override suspend fun handleRequest(
                request: Libp2pRpcProtocol.RpcRequest,
                effectHandler: EffectPropagator,
            ): Libp2pRpcProtocol.RpcResponse {
                handlerEntered.countDown()
                try {
                    assertTrue(
                        releaseHandler.await(10, TimeUnit.SECONDS),
                        "The test did not release the bytecode request handler",
                    )
                } finally {
                    handlerReturned.countDown()
                }
                return Libp2pRpcProtocol.RpcResponse.success(request.id, "computed")
            }
        },
        keyPair = Libp2pHostFactory.generateKeyFromSeed("ILOG1b-remote-retirement-server"),
        idleTimeoutSeconds = 120,
    )
    val provider = Libp2pNetworkProvider(
        keySeed = "ILOG1b-remote-retirement-client",
        listenHost = "127.0.0.1",
        enableRelay = false,
    )
    val observedStream = java.util.concurrent.atomic.AtomicReference<RpcStream>()
    val observedProvider = object : NetworkProvider by provider {
        override fun dial(peerId: String, address: String, protocolId: String, timeoutMs: Long): RpcStream =
            provider.dial(peerId, address, protocolId, timeoutMs).also { observedStream.set(it) }
    }
    val protocol = UrlProtocol(
        bootstrapPeers = emptyList(),
        network = observedProvider,
        relayConfig = RelayConfig(enableRelay = false),
        lanDiscoveryConfig = LanDiscoveryConfig(enabled = false),
    )
    val executor = Executors.newSingleThreadExecutor()

    try {
        serverHost.start().get(10, TimeUnit.SECONDS)
        val serverPeerId = serverHost.peerId.toBase58()
        val clientInfo = Effective {
            handler { effect: ConnectionClosedEffect ->
                if (effect.remotePeerId == serverPeerId) closed.complete(effect)
                Unit
            }
            provider.listen(0)
        }
        val peer = Libp2pPeer.remote(
            serverPeerId,
            serverHost.listenAddresses().map { it.toString() },
        )
        val beforeDial = System.currentTimeMillis()
        val pending = executor.submit<Any> {
            protocol.requestBytecodeConditionallyFromPeer(
                peer = peer,
                serviceIdentifier = service,
                interfaceClassName = null,
                cachedDigest = null,
                requestTimeoutMs = 15_000,
                connectionTimeoutMs = 1_000,
            )
        }

        val reachedHandler = handlerEntered.await(10, TimeUnit.SECONDS)
        if (!reachedHandler && pending.isDone) pending.get(1, TimeUnit.SECONDS)
        assertTrue(
            reachedHandler,
            "The bytecode request must reach the real server handler",
        )
        val serverConnection = serverHost.network.connections.single()
        val serverRoot = rootConnectionChannel((serverConnection as P2PChannelOverNetty).nettyChannel)
        val serverLocalAddress = serverConnection.localAddress().toString()
        val serverRemoteAddress = serverConnection.remoteAddress().toString()
        val serverStream = serverHost.streams.single() as? StreamOverNetty
            ?: fail("Could not obtain the accepted bytecode RPC stream")
        val muxId = (serverStream.nettyChannel.id() as? MuxId)?.id?.let { "outbound:$it" }
            ?: fail("The accepted bytecode RPC stream did not expose a numeric mux ID")

        val initialSnapshot = assertNotNull((assertNotNull(observedStream.get()) as RootLinkDiagnosticSource).rootLinkDiagnostics)
        assertEquals(listOf(muxId), initialSnapshot.substreamIds)
        assertEquals(1, initialSnapshot.inFlightSubstreams)

        serverRoot.attr(
            AttributeKey.valueOf<Consumer<String>>(
                "foundation.url.protocol.awaitedResponseOutboxObserver",
            ),
        ).set(
            Consumer { event ->
                outboxEvents.add(event)
                if (event.startsWith("ADMITTED:")) responseAdmitted.countDown()
            },
        )
        val forcedUnwritable = serverRoot.eventLoop().submit {
            val outbound = serverRoot.unsafe().outboundBuffer()
                ?: fail("The server root connection has no outbound buffer")
            outbound.setUserDefinedWritability(27, false)
        }
        forcedUnwritable.get(10, TimeUnit.SECONDS)
        assertFalse(serverRoot.isWritable, "The server root must be forced unwritable")

        releaseHandler.countDown()
        assertTrue(
            handlerReturned.await(10, TimeUnit.SECONDS),
            "The real handler must compute its response while the root is unwritable",
        )
        assertTrue(
            responseAdmitted.await(10, TimeUnit.SECONDS),
            "The computed response must enter the production outbox; events=$outboxEvents",
        )
        val afterAdmission = System.currentTimeMillis()

        val failure = assertFailsWith<ExecutionException> {
            pending.get(15, TimeUnit.SECONDS)
        }.cause
        assertIs<UrlProtocolException>(failure)
        val message = assertNotNull(failure.message)
        val openedAt = assertNotNull(
            Regex("openedAtMillis=([0-9]+)").find(message),
            "The bytecode failure must carry its local root identity: $message",
        ).groupValues[1].toLong()
        assertTrue(openedAt in beforeDial..afterAdmission)

        val localClose = closed.get(10, TimeUnit.SECONDS)
        assertEquals(ConnectionCloseInitiator.REMOTE_END_OF_STREAM, localClose.initiator)
        assertEquals(serverPeerId, localClose.remotePeerId)
        assertEquals(serverRemoteAddress, localClose.localAddress)
        assertEquals(serverLocalAddress, localClose.remoteAddress)
        val diagnostics = assertNotNull((assertNotNull(observedStream.get()) as RootLinkDiagnosticSource).rootLinkDiagnostics)
        assertEquals(localClose.connectionIdentity, diagnostics.connectionIdentity)
        assertEquals(clientInfo.peerId, diagnostics.localPeerId)
        assertEquals(peer.peerId, diagnostics.remotePeerId)
        assertEquals(serverRemoteAddress, diagnostics.localEndpoint)
        assertEquals(serverLocalAddress, diagnostics.remoteEndpoint)
        assertEquals(openedAt, diagnostics.openedAtMillis)
        assertFalse(serverStream.nettyChannel.isOpen, "The terminal reply ended its child before the physical root ended")
        assertEquals(emptyList<String>(), diagnostics.substreamIds)
        assertEquals(0, diagnostics.inFlightSubstreams)
        assertEquals(listOf(muxId), initialSnapshot.substreamIds, "The earlier immutable snapshot keeps the child it observed")
        assertEquals("none", diagnostics.reason)
        assertEquals("REMOTE_END_OF_STREAM", diagnostics.initiatedBy)
        assertEquals(expectedOutboxMessage, diagnostics.remoteReportedReason)
        assertTrue(
            outboxEvents.any { it.startsWith("ADMITTED:") },
            "The expected response must have been admitted before retirement; events=$outboxEvents",
        )

        val expectedMessage =
            "Bytecode request for service '$service' to peer '${peer.peerId}' at addresses " +
                "${peer.multiaddresses} failed: OUTBOX_FULL - $expectedOutboxMessage. " +
                "Root link ${localClose.connectionIdentity}: localPeerId=${clientInfo.peerId}; " +
                "remotePeerId=${peer.peerId}; localEndpoint=$serverRemoteAddress; " +
                "remoteEndpoint=$serverLocalAddress; openedAtMillis=$openedAt; " +
                "substreamIds=[]; inFlightSubstreams=0; reason=none; " +
                "initiatedBy=REMOTE_END_OF_STREAM; remoteReportedReason=$expectedOutboxMessage."
        assertEquals(expectedMessage, message)
    } finally {
        releaseHandler.countDown()
        try {
            protocol.close()
        } finally {
            try {
                serverHost.stop().get(10, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }
}
