package io.libp2p.protocol

import io.libp2p.core.BadPeerException
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toHex
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface BlobController {
    fun blob(): CompletableFuture<Long>
}

class Blob(blobSize: Int) : BlobBinding(BlobProtocol(blobSize))

open class BlobBinding(blob: BlobProtocol) :
    StrictProtocolBinding<BlobController>("/ipfs/blob-echo/1.0.0", blob)

class BlobTimeoutException : Libp2pException()

open class BlobProtocol(var blobSize: Int) : ProtocolHandler<BlobController>(Long.MAX_VALUE, Long.MAX_VALUE) {
    private val schedulerLock = Any()
    private val activeInitiators = Collections.newSetFromMap(IdentityHashMap<BlobInitiator, Boolean>())
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

    private fun registerInitiator(initiator: BlobInitiator) {
        synchronized(schedulerLock) { activeInitiators.add(initiator) }
    }

    private fun releaseInitiator(initiator: BlobInitiator) {
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
    var blobTimeout = Duration.ofSeconds(10)

    override fun onStartInitiator(stream: Stream): CompletableFuture<BlobController> {
        val handler = BlobInitiator()
        stream.pushHandler(BlobCodec())
        stream.pushHandler(handler)
        stream.pushHandler(BlobCodec())
        return handler.activeFuture
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<BlobController> {
        val handler = BlobResponder()
        stream.pushHandler(BlobCodec())
        stream.pushHandler(BlobResponder())
        stream.pushHandler(BlobCodec())
        return CompletableFuture.completedFuture(handler)
    }

    open class BlobCodec : ByteToMessageCodec<ByteArray>() {
        override fun encode(ctx: ChannelHandlerContext?, msg: ByteArray, out: ByteBuf) {
            println("Codec::encode")
            out.writeInt(msg.size)
            out.writeBytes(msg)
        }

        override fun decode(ctx: ChannelHandlerContext?, msg: ByteBuf, out: MutableList<Any>) {
            println("Codec::decode " + msg.readableBytes())
            val readerIndex = msg.readerIndex()
            if (msg.readableBytes() < 4) {
                return
            }
            val len = msg.readInt()
            if (msg.readableBytes() < len) {
                // not enough data to read the full array
                // will wait for more ...
                msg.readerIndex(readerIndex)
                return
            }
            val data = msg.readSlice(len)
            out.add(data.toByteArray())
        }
    }

    open inner class BlobResponder : ProtocolMessageHandler<ByteArray>, BlobController {
        override fun onMessage(stream: Stream, msg: ByteArray) {
            println("Responder::onMessage")
            stream.writeAndFlush(msg)
        }

        override fun blob(): CompletableFuture<Long> {
            throw Libp2pException("This is blob responder only")
        }
    }

    open inner class BlobInitiator : ProtocolMessageHandler<ByteArray>, BlobController {
        val activeFuture = CompletableFuture<BlobController>()
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

        override fun onMessage(stream: Stream, msg: ByteArray) {
            println("Initiator::onMessage")
            val dataS = msg.toHex()
            val (request, timer) = synchronized(requests) {
                requests.remove(dataS) to requestTimers.remove(dataS)
            }
            val (sentT, future) = request
                ?: throw BadPeerException("Unknown or expired blob data in response: $dataS")
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

        override fun blob(): CompletableFuture<Long> {
            val ret = CompletableFuture<Long>()
            val data = ByteArray(blobSize)
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
                            expired?.completeExceptionally(BlobTimeoutException())
                        },
                        blobTimeout.toMillis(),
                        TimeUnit.MILLISECONDS
                    )
                    if (requests.containsKey(dataS)) requestTimers[dataS] = timer else timer.cancel(false)
                } catch (failure: Throwable) {
                    requests.remove(dataS)
                    throw failure
                }
            }

            println("Sender writing " + blobSize)
            stream.writeAndFlush(data)
            return ret
        }
    }
}
