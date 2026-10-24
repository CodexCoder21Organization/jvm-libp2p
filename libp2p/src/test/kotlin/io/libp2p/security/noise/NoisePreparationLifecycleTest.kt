package io.libp2p.security.noise

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.Libp2pException
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.DefaultEventLoop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.ProviderException
import java.security.Security
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NoisePreparationLifecycleTest {
    @Test
    fun `stop fails pending readiness start listen and connect without listen addresses`() = lifecycleProbe("close")

    @Test
    fun `dial and start after stop fail while shared preparation is held`() = lifecycleProbe("after-stop")

    @Test
    fun `stop reentered from readiness prevents empty listener startup`() = lifecycleProbe("ready-close")

    @Test
    fun `direct network listen and connect await preparation without start`() = lifecycleProbe("direct")

    @Test
    fun `direct network listen and connect preserve pending and completed preparation failure`() = lifecycleProbe("failure")
}

private fun lifecycleProbe(mode: String) {
    // The peer belongs to the parent. Its startup cannot consume the cold subject's
    // preparation/lifecycle budget or initialize Noise in that subject JVM.
    val peer = if (mode == "direct" || mode == "failure") {
        host {
            identity { random(KeyType.ED25519) }
            muxers { +StreamMuxerProtocol.getYamux() }
            protocols { +Ping() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
        }
    } else {
        null
    }
    try {
        peer?.start()?.get(10, TimeUnit.SECONDS)
        check(peer == null || peer.network.connections.isEmpty()) {
            "Host start must prepare the transport without opening a connection"
        }
        val output = Files.createTempFile("noise-lifecycle-", ".log")
        try {
            val command = listOf(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-Xmx128m",
                "-Dio.netty.eventLoopThreads=1",
                "-cp",
                System.getProperty("java.class.path"),
                "io.libp2p.security.noise.NoisePreparationLifecycleProbe",
                mode
            ) + listOfNotNull(peer?.listenAddresses()?.single()?.toString())
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue(awaitNoiseProbe(process, output), "Cold lifecycle probe did not finish")
                val text = Files.readString(output)
                print(text)
                assertEquals(0, process.exitValue(), text)
            } finally {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            Files.deleteIfExists(output)
        }
    } finally {
        peer?.stop()?.get(5, TimeUnit.SECONDS)
    }
}

object NoisePreparationLifecycleProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.first()
        val endpoint = args.getOrNull(1)
        check(args.size == if (mode == "direct" || mode == "failure") 2 else 1) {
            "Lifecycle mode $mode requires exactly one prepared peer endpoint for direct network calls"
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = ProviderException("Noise preparation failure requested by lifecycle test")
        val provider = NoisePreparationProvider {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Lifecycle preparation was not released" }
            if (mode == "failure") throw failure
        }
        val loop = DefaultEventLoop()
        val hosts = mutableListOf<Host>()
        try {
            check(Security.insertProviderAt(provider, 1) == 1)
            noiseProbePhase("READY")
            val owner = AtomicReference<Host>()
            val pending = loop.submit(
                Callable {
                    lifecycleHost {
                        if (mode == "ready-close") owner.get()?.stop()?.get(10, TimeUnit.SECONDS)
                    }
                }
            ).get(10, TimeUnit.SECONDS)
            owner.set(pending)
            hosts += pending
            check(entered.await(10, TimeUnit.SECONDS)) { "Noise preparation did not enter the provider" }
            val ready = pending.network.transports.single().ready()
            val cancelledObserver = pending.network.transports.single().ready()
            cancelledObserver.cancel(false)
            check(!ready.isDone) { "Cancelling one readiness observer settled another" }
            val address = endpoint?.let { Multiaddr(it) } ?: Multiaddr("/ip4/127.0.0.1/tcp/1")
            val peerId = address.getPeerId() ?: pending.peerId
            if (mode == "ready-close") {
                val starting = pending.start()
                release.countDown()
                starting.handle { _, _ -> Unit }.get(10, TimeUnit.SECONDS)
                expectClosed(starting)
                check(pending.listenAddresses().isEmpty() && pending.network.connections.isEmpty())
            } else if (mode == "close") {
                val starting = pending.start()
                val listening = pending.network.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"))
                val connecting = pending.network.connect(peerId, address)
                check(listOf(ready, starting, listening, connecting).none { it.isDone }) {
                    "An operation completed while preparation was held"
                }
                pending.stop().get(10, TimeUnit.SECONDS)
                listOf(ready, starting, listening, connecting).forEach { expectClosed(it) }
                check(pending.listenAddresses().isEmpty() && pending.network.connections.isEmpty())
            } else if (mode == "after-stop") {
                val survivor = loop.submit(Callable { lifecycleHost() }).get(10, TimeUnit.SECONDS)
                hosts += survivor
                pending.stop().get(10, TimeUnit.SECONDS)
                expectClosed(pending.network.connect(peerId, address))
                expectClosed(pending.start())
                expectClosed(pending.network.transports.single().ready())
                try {
                    pending.network.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"))
                    error("Listening after stop did not throw the closed error")
                } catch (closed: Libp2pException) {
                    check(closed.message == "Transport is closed")
                }
                val transport = pending.network.transports.single()
                val handler = ConnectionHandler.create { error("Stopped transport invoked a connection callback") }
                expectClosedCall { transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), handler) }
                expectClosedCall { transport.dial(address, handler) }
                release.countDown()
                survivor.start().get(10, TimeUnit.SECONDS)
                survivor.network.transports.single().ready().get(10, TimeUnit.SECONDS)
                check(provider.allocations.get() == 2) { "Stopping one host cancelled shared preparation" }
                check(provider.active.get() == 0 && provider.destroyed.get() == 2) {
                    "Survivor became ready before shared preparation completed"
                }
                // Retry every public entry after confirmed shared completion, not only
                // while the provider gate still keeps readiness pending.
                expectClosed(pending.start())
                expectClosed(transport.ready())
                expectClosed(pending.network.connect(peerId, address))
                expectClosedCall { pending.network.listen(Multiaddr("/ip4/127.0.0.1/tcp/0")) }
                expectClosedCall { transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), handler) }
                expectClosedCall { transport.dial(address, handler) }
                check(pending.listenAddresses().isEmpty() && pending.network.connections.isEmpty()) {
                    "Stopped host published after shared preparation completed"
                }
                check(transport.activeListeners == 0 && transport.activeConnections == 0) {
                    "Stopped transport opened a socket after shared preparation completed"
                }
            } else {
                val listening = pending.network.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"))
                val connecting = pending.network.connect(peerId, address)
                check(pending.network.transports.single().activeConnections == 0) {
                    "Direct connect opened a socket before preparation"
                }
                check(!listening.isDone && !connecting.isDone) { "Direct network operation bypassed pending preparation" }
                check(pending.listenAddresses().isEmpty() && pending.network.connections.isEmpty()) {
                    "Direct network operation published before preparation"
                }
                loop.submit {}.get(1, TimeUnit.SECONDS)
                release.countDown()
                if (mode == "failure") {
                    listOf(ready, listening, connecting).forEach { expectPreparationFailure(it, failure) }
                    expectPreparationFailure(pending.network.listen(Multiaddr("/ip4/127.0.0.1/tcp/0")), failure)
                    expectPreparationFailure(pending.network.connect(peerId, address), failure)
                    check(pending.listenAddresses().isEmpty() && pending.network.connections.isEmpty())
                    pending.stop().get(10, TimeUnit.SECONDS)
                    expectClosed(pending.network.connect(peerId, address))
                } else {
                    listening.get(10, TimeUnit.SECONDS)
                    connecting.get(10, TimeUnit.SECONDS)
                    check(pending.listenAddresses().size == 1)
                    val stream = pending.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), peerId, address)
                    stream.controller.get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS)
                    stream.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                    pending.stop().get(10, TimeUnit.SECONDS)
                    expectClosed(pending.network.transports.single().ready())
                }
            }
            release.countDown()
            noiseProbePhase("BODY_COMPLETE")
            println("LIFECYCLE_MODE=$mode PASSED=true")
        } finally {
            release.countDown()
            try {
                hosts.forEach { it.stop().get(10, TimeUnit.SECONDS) }
            } finally {
                Security.removeProvider(provider.name)
                loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
                noiseProbePhase("CLEANUP_COMPLETE")
            }
        }
    }
}

private fun lifecycleHost(onReady: () -> Unit = {}): Host = host {
    transports {
        add { upgrader ->
            object : TcpTransport(upgrader) {
                override fun ready(): CompletableFuture<Unit> = super.ready().thenApply {
                    onReady()
                    Unit
                }
            }
        }
    }
    identity { random(KeyType.ED25519) }
    muxers { +StreamMuxerProtocol.getYamux() }
    protocols { +Ping() }
}

private fun expectClosed(future: CompletableFuture<*>) {
    check(future.isDone) { "Stopped transport left an operation pending on preparation" }
    try {
        future.get(1, TimeUnit.SECONDS)
        error("Stopped transport completed an operation successfully")
    } catch (error: ExecutionException) {
        var cause: Throwable = error
        while (cause.cause != null) cause = requireNotNull(cause.cause)
        check(cause is Libp2pException && cause.message == "Transport is closed") { "Wrong closed error: $cause" }
    }
}

private fun expectPreparationFailure(future: CompletableFuture<*>, expected: Throwable) {
    try {
        future.get(10, TimeUnit.SECONDS)
        error("Failed preparation completed an operation successfully")
    } catch (error: ExecutionException) {
        var cause: Throwable = error
        while (cause.cause != null) cause = requireNotNull(cause.cause)
        check(cause === expected && cause.message == expected.message) { "Lost original preparation failure: $cause" }
    }
}

private fun expectClosedCall(call: () -> CompletableFuture<*>) {
    val result = try {
        call()
    } catch (closed: Libp2pException) {
        check(closed.message == "Transport is closed") { "Wrong closed error: $closed" }
        return
    }
    expectClosed(result)
}
