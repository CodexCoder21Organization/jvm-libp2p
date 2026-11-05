package io.libp2p.transport

import com.sun.management.UnixOperatingSystemMXBean
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.EventLoopGroup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val F93F_CYCLES = 64
private const val F93F_DIALS_PER_CYCLE = 16
private const val F93F_STEP_TIMEOUT_SECONDS = 10L

class F93fQuicLifecycleSoakTest {
    @Test
    fun repeatedConcurrentQuicLifecyclesReleaseChannelsGroupsThreadsAndDescriptors() {
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-ea", "-cp", System.getProperty("java.class.path"),
            "io.libp2p.transport.F93fQuicLifecycleSoakProbe"
        ).inheritIO().start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "The QUIC lifecycle soak probe must finish")
            assertEquals(0, process.exitValue(), "The QUIC lifecycle soak probe must pass every assertion")
        } finally {
            assertTrue(process.destroyForcibly().waitFor(5, TimeUnit.SECONDS), "The owned lifecycle soak probe JVM must exit during cleanup")
        }
    }
}
object F93fQuicLifecycleSoakProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        // Four dependency event loops match the documented two-core test runner on every host.
        // All sixteen public callers still compete; host CPU count must not size this fixture.
        System.setProperty("io.netty.eventLoopThreads", "4")
        val descriptorBean = ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean
            ?: throw AssertionError("The lifecycle soak requires Unix open-file-descriptor counters")

        // One full cycle warms up the QUIC provider, TLS, Netty, and the JMX counters.
        val warmupDescriptors = runLifecycleCycle("warmup", descriptorBean)

        // Each measured cycle uses a fresh server/client pair, 16 public dial callers released
        // together, concurrent public connection closes, and full transport shutdown.
        repeat(F93F_CYCLES) { cycle ->
            val descriptors = runLifecycleCycle("cycle-$cycle", descriptorBean)
            assertEquals(
                warmupDescriptors,
                descriptors,
                "Open file descriptors must return to the post-warmup baseline after cycle $cycle"
            )
        }
    }
}

private fun runLifecycleCycle(
    cycleName: String,
    descriptorBean: UnixOperatingSystemMXBean
): Long {
    val serverKey = generateEd25519KeyPair().first
    val server = QuicTransport.ECDSA(serverKey, emptyList())
    val client = QuicTransport.ECDSA(generateEd25519KeyPair().first, emptyList())
    val acceptedConnections = ConcurrentLinkedQueue<ConnectionOverNetty>()
    val acceptedCount = CountDownLatch(F93F_DIALS_PER_CYCLE)
    val callerThreads = ConcurrentLinkedQueue<Thread>()
    val callerExecutor = Executors.newFixedThreadPool(F93F_DIALS_PER_CYCLE)
    var clientClosed = false
    var serverClosed = false
    var executorTerminated = false
    try {
        server.listen(Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), { connection ->
            acceptedConnections.add(connection as ConnectionOverNetty)
            acceptedCount.countDown()
        }, null).get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val address = server.listenAddresses().single()
            .withP2P(PeerId.fromPubKey(serverKey.publicKey()))

        val dialReady = CountDownLatch(F93F_DIALS_PER_CYCLE)
        val startDials = CountDownLatch(1)
        val dialTasks = (0 until F93F_DIALS_PER_CYCLE).map {
            callerExecutor.submit<ConnectionOverNetty> {
                callerThreads.add(Thread.currentThread())
                dialReady.countDown()
                check(startDials.await(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "$cycleName dial callers must be released together"
                }
                client.dial(address, { }, null)
                    .get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS) as ConnectionOverNetty
            }
        }
        assertTrue(
            dialReady.await(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "$cycleName must start all $F93F_DIALS_PER_CYCLE concurrent public dial callers"
        )
        startDials.countDown()
        val clientConnections = dialTasks.map { it.get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        assertEquals(F93F_DIALS_PER_CYCLE, clientConnections.size)
        assertTrue(
            acceptedCount.await(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "$cycleName server must observe all ${clientConnections.size} successful dials"
        )
        val serverConnections = acceptedConnections.toList()
        assertEquals(F93F_DIALS_PER_CYCLE, serverConnections.size)

        val clientChannels = uniquePhysicalChannels(clientConnections)
        val serverChannels = uniquePhysicalChannels(serverConnections)
        assertEquals(F93F_DIALS_PER_CYCLE * 2, clientChannels.size, "$cycleName must observe every client QUIC child and UDP parent")
        assertEquals(F93F_DIALS_PER_CYCLE + 1, serverChannels.size, "$cycleName must observe every server QUIC child and its shared listener parent")
        val allChannels = identityDistinct(clientChannels + serverChannels)
        val observations = allChannels.map { channel -> observeUnregistration("$cycleName ${channel.id()}", channel) }

        val clientGroup = clientConnections.first().nettyChannel.eventLoop().parent()
        val serverGroup = serverConnections.first().nettyChannel.eventLoop().parent()
        assertNotSame(clientGroup, serverGroup, "$cycleName must own separate client and server groups")
        assertTrue(clientConnections.all { it.nettyChannel.eventLoop().parent() === clientGroup })
        assertTrue(serverConnections.all { it.nettyChannel.eventLoop().parent() === serverGroup })

        // Submit to every loop in each owned group so shutdown cannot hide a lazy loop thread.
        val clientThreads = captureGroupThreads(clientGroup)
        val serverThreads = captureGroupThreads(serverGroup)
        assertTrue(clientThreads.isNotEmpty(), "$cycleName must observe client group threads")
        assertTrue(serverThreads.isNotEmpty(), "$cycleName must observe server group threads")

        val closeReady = CountDownLatch(clientConnections.size)
        val startCloses = CountDownLatch(1)
        val closeTasks = clientConnections.map { connection ->
            callerExecutor.submit<Unit> {
                callerThreads.add(Thread.currentThread())
                closeReady.countDown()
                check(startCloses.await(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "$cycleName connection closers must be released together"
                }
                connection.close().get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        assertTrue(closeReady.await(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "$cycleName must start all connection closers")
        startCloses.countDown()
        closeTasks.forEach { it.get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

        val clientChannelSet = identitySet(clientChannels)
        observations.filter { clientChannelSet.contains(it.channel) }
            .forEach { it.awaitClosed("$cycleName client channel") }
        assertEquals(0, client.activeConnections, "$cycleName client must unregister every child and UDP parent while still live")
        assertFalse(clientGroup.isShuttingDown, "$cycleName successful parent churn must leave the client transport usable until close")

        callerExecutor.shutdown()
        assertTrue(callerExecutor.awaitTermination(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "$cycleName public callers must finish")
        executorTerminated = true
        assertTrue(callerThreads.isNotEmpty(), "$cycleName must use real concurrent caller threads")
        joinThreads(callerThreads.distinct(), "$cycleName public caller")
        assertTrue(callerThreads.none { it.isAlive }, "$cycleName caller threads must end before the resource sample")

        client.close().get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        clientClosed = true
        assertTrue(clientGroup.isTerminated, "$cycleName client worker group must terminate")
        joinThreads(clientThreads, "$cycleName client event-loop")
        assertTrue(clientThreads.none { it.isAlive }, "$cycleName every client event-loop thread must have ended")

        server.close().get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        serverClosed = true
        assertTrue(serverGroup.isTerminated, "$cycleName server worker group must terminate")
        joinThreads(serverThreads, "$cycleName server event-loop")
        assertTrue(serverThreads.none { it.isAlive }, "$cycleName every server event-loop thread must have ended")

        observations.forEach { it.awaitClosed("$cycleName observed physical channel") }
        assertEquals(0, client.activeConnections, "$cycleName client must have no registered channels after shutdown")
        assertEquals(0, server.activeConnections, "$cycleName server must have no registered channels after shutdown")
        assertEquals(0, server.activeListeners, "$cycleName server listener must be removed after shutdown")
        assertTrue(clientThreads.none { it.isAlive } && serverThreads.none { it.isAlive }, "$cycleName must leave no owned event-loop thread alive")

        val descriptors = descriptorBean.openFileDescriptorCount
        assertTrue(descriptors >= 0, "$cycleName Unix descriptor count must be available")
        return descriptors
    } finally {
        try {
            if (!clientClosed) {
                client.close().get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } finally {
            try {
                if (!serverClosed) {
                    server.close().get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            } finally {
                if (!executorTerminated) {
                    callerExecutor.shutdownNow()
                    assertTrue(callerExecutor.awaitTermination(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "$cycleName caller executor must terminate during cleanup")
                    joinThreads(callerThreads.distinct(), "$cycleName public caller cleanup")
                }
            }
        }
    }
}

private fun uniquePhysicalChannels(connections: List<ConnectionOverNetty>): List<Channel> {
    val channels = connections.flatMap { connection ->
        val child = connection.nettyChannel
        listOf(child, requireNotNull(child.parent()) { "Every QUIC child must have its physical UDP parent" })
    }
    return identityDistinct(channels)
}

private fun identityDistinct(channels: List<Channel>): List<Channel> {
    val seen = identitySet<Channel>()
    return channels.filter { seen.add(it) }
}

private fun <T : Any> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

private fun identitySet(channels: List<Channel>): MutableSet<Channel> {
    val result = identitySet<Channel>()
    result.addAll(channels)
    return result
}

private fun observeUnregistration(label: String, channel: Channel): ObservedChannel {
    val unregistered = CompletableFuture<Unit>()
    channel.eventLoop().submit {
        channel.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
            override fun channelUnregistered(ctx: ChannelHandlerContext) {
                unregistered.complete(Unit)
                ctx.fireChannelUnregistered()
            }
        })
    }.get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return ObservedChannel(label, channel, unregistered)
}

private fun captureGroupThreads(group: EventLoopGroup): List<Thread> = group.map { loop ->
    loop.submit<Thread> { Thread.currentThread() }.get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
}.distinct()

private fun joinThreads(threads: List<Thread>, label: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(F93F_STEP_TIMEOUT_SECONDS)
    threads.forEach { thread ->
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos > 0L) {
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
            thread.join(remainingMillis)
        }
    }
    assertTrue(threads.none { it.isAlive }, "$label threads must end within ${F93F_STEP_TIMEOUT_SECONDS} seconds")
}

private data class ObservedChannel(
    val label: String,
    val channel: Channel,
    val unregistered: CompletableFuture<Unit>
) {
    fun awaitClosed(context: String) {
        assertTrue(channel.closeFuture().await(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "$context $label close future must complete")
        assertTrue(channel.closeFuture().isSuccess, "$context $label close must succeed")
        unregistered.get(F93F_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertFalse(channel.isOpen, "$context $label must be physically closed")
        assertFalse(channel.isRegistered, "$context $label must be unregistered after channelUnregistered")
    }
}
