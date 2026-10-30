package io.libp2p.protocol

import io.libp2p.core.BadPeerException
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toHex
import io.netty.buffer.ByteBuf
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
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
    var timeoutScheduler: ScheduledExecutorService by lazyVar { ScheduledThreadPoolExecutor(1) }
    private val schedulerMonitor = Any()
    private val schedulerUsers = IdentityHashMap<ScheduledExecutorService, Int>()
    var curTime: () -> Long = { System.currentTimeMillis() }
    var random = Random()
    var pingTimeout = Duration.ofSeconds(10)

    constructor() : this(32)

    override fun onStartInitiator(stream: Stream): CompletableFuture<PingController> {
        val handler = PingInitiator()
        try {
            stream.pushHandler(handler)
        } catch (failure: Throwable) {
            try {
                handler.onClosed(stream)
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
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
        private val timeoutTasks = mutableMapOf<String, ScheduledFuture<*>>()
        private var schedulerReleased = false
        private val scheduler = synchronized(schedulerMonitor) {
            timeoutScheduler.also { schedulerUsers[it] = (schedulerUsers[it] ?: 0) + 1 }
        }
        lateinit var stream: Stream
        var closed = false

        override fun onActivated(stream: Stream) {
            this.stream = stream
            activeFuture.complete(this)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            val dataS = msg.toByteArray().toHex()
            val request = synchronized(requests) {
                requests.remove(dataS).also {
                    if (it != null) timeoutTasks.remove(dataS)?.cancel(false)
                }
            } ?: throw BadPeerException("Unknown or expired ping data in response: $dataS")
            val (sentT, future) = request
            future.complete(curTime() - sentT)
        }

        override fun onClosed(stream: Stream) {
            val pending: List<CompletableFuture<Long>>
            val schedulerToClose: ScheduledExecutorService?
            synchronized(requests) {
                if (schedulerReleased) return
                schedulerReleased = true
                closed = true
                pending = requests.values.map { it.second }
                requests.clear()
                timeoutTasks.values.forEach { it.cancel(false) }
                timeoutTasks.clear()
                schedulerToClose = synchronized(schedulerMonitor) {
                    val remaining = checkNotNull(schedulerUsers[scheduler]) - 1
                    if (remaining > 0) {
                        schedulerUsers[scheduler] = remaining
                        null
                    } else {
                        schedulerUsers.remove(scheduler)
                        if (timeoutScheduler === scheduler) {
                            timeoutScheduler = ScheduledThreadPoolExecutor(1)
                        }
                        scheduler
                    }
                }
            }
            pending.forEach { it.completeExceptionally(ConnectionClosedException()) }
            schedulerToClose?.shutdownNow()
            activeFuture.completeExceptionally(ConnectionClosedException())
        }

        override fun ping(): CompletableFuture<Long> {
            val ret = CompletableFuture<Long>()
            val data = ByteArray(pingSize)
            random.nextBytes(data)
            val dataS = data.toHex()

            synchronized(requests) {
                if (closed || schedulerReleased) return completedExceptionally(ConnectionClosedException())
                requests[dataS] = curTime() to ret

                val timeout = try {
                    scheduler.schedule(
                        {
                            val request = synchronized(requests) {
                                timeoutTasks.remove(dataS)
                                requests.remove(dataS)
                            }
                            request?.second?.completeExceptionally(PingTimeoutException())
                        },
                        pingTimeout.toMillis(),
                        TimeUnit.MILLISECONDS
                    )
                } catch (failure: Throwable) {
                    requests.remove(dataS)
                    throw failure
                }
                if (requests.containsKey(dataS)) {
                    timeoutTasks[dataS] = timeout
                } else {
                    timeout.cancel(false)
                }
            }

            stream.writeAndFlush(data.toByteBuf())
            return ret
        }
    }
}
