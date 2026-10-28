package io.libp2p.protocol

import io.libp2p.core.BadPeerException
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toHex
import io.netty.buffer.ByteBuf
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface PingController {
    fun ping(): CompletableFuture<Long>
}

class Ping(pingSize: Int) : PingBinding(PingProtocol(pingSize)) {
    constructor() : this(32)
}

open class PingBinding(ping: PingProtocol) :
    StrictProtocolBinding<PingController>("/ipfs/ping/1.0.0", ping)

class PingTimeoutException : Libp2pException()

open class PingProtocol(var pingSize: Int) : ProtocolHandler<PingController>(Long.MAX_VALUE, Long.MAX_VALUE) {
    private val schedulerLock = Any()
    private val activeInitiators = Collections.newSetFromMap(IdentityHashMap<PingInitiator, Boolean>())
    private val ownedSchedulers = mutableSetOf<ScheduledExecutorService>()
    private var currentScheduler: ScheduledExecutorService? = null

    /** An explicitly supplied scheduler stays owned by the caller. */
    var timeoutScheduler: ScheduledExecutorService
        get() = synchronized(schedulerLock) {
            currentScheduler ?: Executors.newSingleThreadScheduledExecutor().also {
                currentScheduler = it
                ownedSchedulers.add(it)
            }
        }
        set(value) = synchronized(schedulerLock) { currentScheduler = value }

    private fun registerInitiator(initiator: PingInitiator) {
        synchronized(schedulerLock) { activeInitiators.add(initiator) }
    }

    private fun releaseInitiator(initiator: PingInitiator) {
        val retiring = synchronized(schedulerLock) {
            if (!activeInitiators.remove(initiator) || activeInitiators.isNotEmpty()) {
                emptyList()
            } else {
                ownedSchedulers.toList().also { owned ->
                    ownedSchedulers.clear()
                    if (owned.any { it === currentScheduler }) currentScheduler = null
                }
            }
        }
        // The protocol owns these defaults. Stream callbacks only release their registration.
        retiring.forEach { it.shutdownNow() }
    }
    var curTime: () -> Long = { System.currentTimeMillis() }
    var random = Random()
    var pingTimeout = Duration.ofSeconds(10)

    constructor() : this(32)

    override fun onStartInitiator(stream: Stream): CompletableFuture<PingController> {
        val handler = PingInitiator()
        stream.pushHandler(handler)
        return handler.activeFuture
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<PingController> {
        val handler = PingResponder()
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    open inner class PingResponder : ProtocolMessageHandler<ByteBuf>, PingController {
        override fun onMessage(stream: Stream, msg: ByteBuf) {
            stream.writeAndFlush(msg)
        }

        override fun ping(): CompletableFuture<Long> {
            throw Libp2pException("This is ping responder only")
        }
    }

    open inner class PingInitiator : ProtocolMessageHandler<ByteBuf>, PingController {
        val activeFuture = CompletableFuture<PingController>()
        val requests = Collections.synchronizedMap(mutableMapOf<String, Pair<Long, CompletableFuture<Long>>>())
        lateinit var stream: Stream
        var closed = false
        private val requestTimers = mutableMapOf<String, ScheduledFuture<*>>()

        override fun onActivated(stream: Stream) {
            synchronized(requests) {
                if (closed) return
                this.stream = stream
                registerInitiator(this)
            }
            activeFuture.complete(this)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            val dataS = msg.toByteArray().toHex()
            val (request, timer) = synchronized(requests) {
                requests.remove(dataS) to requestTimers.remove(dataS)
            }
            val (sentT, future) = request
                ?: throw BadPeerException("Unknown or expired ping data in response: $dataS")
            timer?.cancel(false)
            future.complete(curTime() - sentT)
        }

        override fun onClosed(stream: Stream) {
            val (pending, timers) = synchronized(requests) {
                closed = true
                val pending = requests.values.map { it.second }
                val timers = requestTimers.values.toList()
                requests.clear()
                requestTimers.clear()
                pending to timers
            }
            try {
                timers.forEach { it.cancel(false) }
                pending.forEach { it.completeExceptionally(ConnectionClosedException()) }
                activeFuture.completeExceptionally(ConnectionClosedException())
            } finally {
                releaseInitiator(this)
            }
        }

        override fun ping(): CompletableFuture<Long> {
            val ret = CompletableFuture<Long>()
            val data = ByteArray(pingSize)
            random.nextBytes(data)
            val dataS = data.toHex()

            synchronized(requests) {
                if (closed) return completedExceptionally(ConnectionClosedException())
                requests[dataS] = curTime() to ret

                try {
                    val timer = timeoutScheduler.schedule(
                        {
                            val expired = synchronized(requests) {
                                requestTimers.remove(dataS)
                                requests.remove(dataS)?.second
                            }
                            expired?.completeExceptionally(PingTimeoutException())
                        },
                        pingTimeout.toMillis(),
                        TimeUnit.MILLISECONDS
                    )
                    if (requests.containsKey(dataS)) requestTimers[dataS] = timer else timer.cancel(false)
                } catch (failure: Throwable) {
                    requests.remove(dataS)
                    throw failure
                }
            }

            stream.writeAndFlush(data.toByteBuf())
            return ret
        }
    }
}
