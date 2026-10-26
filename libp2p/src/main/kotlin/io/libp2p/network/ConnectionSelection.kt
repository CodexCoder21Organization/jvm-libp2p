package io.libp2p.network

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.NoSuchRemoteProtocolException
import io.libp2p.core.Stream
import io.libp2p.core.StreamPromise
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.hasCauseOfType
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

private const val RETAIN = 1
private const val RETIRE = 2
private const val SELECTION_PROTOCOL = "/libp2p/connection-selection/1.0.0"
private val selectionLogger = LoggerFactory.getLogger("io.libp2p.network.ConnectionSelection")

/** The physical dial initiator orders retention against retirement for BOTH endpoints. */
internal class ConnectionSelection(
    private val connection: Connection,
    private val session: StreamMuxer.Session
) : StreamMuxer.Session {
    private var retained = false
    private var rejected = false
    private var retired = false
    private var reservation: CompletableFuture<Boolean>? = null

    @Synchronized
    fun eligible(): Boolean = !rejected && !connection.closeFuture().isDone

    @Synchronized
    fun retainAtInitiator(): Boolean {
        if (!eligible()) return false
        retained = true
        return true
    }

    @Synchronized
    fun retireAtAcceptor(): Boolean {
        if (retained) return false
        rejected = true
        retired = true
        return true
    }

    @Synchronized
    fun wasRetired(): Boolean = retired

    @Synchronized
    fun closeRequested() {
        rejected = true
    }

    fun reserve(): CompletableFuture<Boolean> {
        if (connection.isInitiator) return CompletableFuture.completedFuture(retainAtInitiator())
        val owned = CompletableFuture<Boolean>()
        synchronized(this) {
            if (!eligible()) return CompletableFuture.completedFuture(false)
            if (retained) return CompletableFuture.completedFuture(true)
            reservation?.let { return it.thenApply { accepted -> accepted } }
            reservation = owned
        }
        request(RETAIN).whenComplete { accepted, failure ->
            val legacy = failure?.hasCauseOfType(NoSuchRemoteProtocolException::class) == true
            if (failure != null && !legacy) {
                owned.completeExceptionally(failure)
            } else {
                if (legacy) selectionLogger.warn("Remote peer does not support connection retention on {}; its own duplicate decisions cannot be coordinated", connection)
                val usable = synchronized(this) {
                    if ((accepted == true || legacy) && eligible()) {
                        retained = true
                    } else {
                        rejected = true
                        if (accepted == false && !legacy) retired = true
                    }
                    retained && !rejected
                }
                owned.complete(usable)
            }
        }
        return owned.thenApply { it }
    }

    fun rejectDuplicate() {
        // Accepted connections are never closed by local duplicate cleanup. Their physical
        // initiator owns retirement; a remote reservation must reach that same decision.
        if (!connection.isInitiator) return
        synchronized(this) {
            if (retained || rejected || connection.closeFuture().isDone) return
            rejected = true
            retired = true
        }
        request(RETIRE).whenComplete { accepted, failure ->
            when {
                failure != null -> selectionLogger.warn(
                    "Keeping duplicate {} because remote retirement could not be confirmed",
                    connection,
                    failure
                )
                accepted -> connection.close()
            }
        }
    }

    private fun request(command: Int): CompletableFuture<Boolean> {
        val promise = try {
            session.createStream(SelectionBinding(command))
        } catch (failure: Exception) {
            return completedExceptionally(failure)
        }
        promise.controller.whenComplete { _, _ ->
            promise.stream.thenAccept { it.close() }
        }
        return promise.controller
    }

    override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
        val stream = CompletableFuture<Stream>()
        val controller = CompletableFuture<T>()
        reserve().whenComplete { accepted, failure ->
            when {
                failure != null -> {
                    stream.completeExceptionally(failure)
                    controller.completeExceptionally(failure)
                }
                !accepted -> {
                    val closed = ConnectionClosedException("Cannot open a stream on a connection selected for duplicate retirement: $connection")
                    stream.completeExceptionally(closed)
                    controller.completeExceptionally(closed)
                }
                else -> {
                    try {
                        val opened = session.createStream(protocols)
                        opened.stream.forward(stream)
                        opened.controller.forward(controller)
                    } catch (failure: Exception) {
                        stream.completeExceptionally(failure)
                        controller.completeExceptionally(failure)
                    }
                }
            }
        }
        return StreamPromise(stream, controller)
    }
}

internal fun connectionEligible(connection: Connection): Boolean =
    !connection.closeFuture().isDone && ((connection as? ConnectionOverNetty)?.selection?.eligible() != false)

internal fun duplicateRetired(connection: Connection): Boolean =
    (connection as? ConnectionOverNetty)?.selection?.wasRetired() == true

internal fun reserveConnection(connection: Connection): CompletableFuture<Boolean> =
    (connection as? ConnectionOverNetty)?.selection?.reserve()
        ?: CompletableFuture.completedFuture(!connection.closeFuture().isDone)

internal fun rejectDuplicate(connection: Connection) {
    // A custom transport without coordination cannot establish that its remote parent is unused.
    (connection as? ConnectionOverNetty)?.selection?.rejectDuplicate()
}

internal class SelectionBinding(command: Int? = null) :
    StrictProtocolBinding<Boolean>(SELECTION_PROTOCOL, SelectionProtocol(command))

private class SelectionProtocol(private val command: Int?) : ProtocolHandler<Boolean>(1, 1) {
    override fun onStartInitiator(stream: Stream): CompletableFuture<Boolean> {
        val response = CompletableFuture<Boolean>()
        val request = command ?: return completedExceptionally(Libp2pException("Connection selection request has no command"))
        stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
            override fun onActivated(stream: Stream) {
                stream.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(request)).whenComplete { _, failure ->
                    if (failure != null) response.completeExceptionally(failure)
                }
            }
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                if (msg.readableBytes() != 1 || msg.getUnsignedByte(msg.readerIndex()).toInt() !in 0..1) {
                    response.completeExceptionally(Libp2pException("Invalid connection selection response: expected one byte containing 0 or 1"))
                } else {
                    response.complete(msg.readUnsignedByte().toInt() == 1)
                }
            }
            override fun onClosed(stream: Stream) {
                response.completeExceptionally(ConnectionClosedException("Connection selection stream closed before its response: $stream"))
            }
            override fun onException(cause: Throwable?) {
                response.completeExceptionally(cause ?: Libp2pException("Connection selection stream failed without a cause"))
            }
        })
        return response
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<Boolean> {
        stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                val connection = stream.connection
                val selection = (connection as? ConnectionOverNetty)?.selection
                val accepted = if (msg.readableBytes() == 1 && selection != null) {
                    when (msg.readUnsignedByte().toInt()) {
                        RETAIN -> connection.isInitiator && selection.retainAtInitiator()
                        RETIRE -> !connection.isInitiator && selection.retireAtAcceptor()
                        else -> false
                    }
                } else {
                    false
                }
                stream.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(if (accepted) 1 else 0))
                    .whenComplete { _, failure ->
                        if (failure != null) selectionLogger.warn("Connection selection response failed on {}", connection, failure)
                        stream.closeWrite()
                    }
            }
            override fun onException(cause: Throwable?) {
                selectionLogger.warn("Connection selection request failed on {}", stream.connection, cause)
                stream.close()
            }
        })
        return CompletableFuture.completedFuture(true)
    }
}
