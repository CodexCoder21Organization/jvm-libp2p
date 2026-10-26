package io.libp2p.network

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.StreamPromise
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

private const val RETIRE = 2
private const val SELECTION_PROTOCOL = "/libp2p/connection-selection/1.0.0"
private val selectionLogger = LoggerFactory.getLogger("io.libp2p.network.ConnectionSelection")

/** Local retention at both endpoints is ordered against duplicate retirement. */
internal class ConnectionSelection(
    private val connection: Connection,
    private val session: StreamMuxer.Session
) : StreamMuxer.Session {
    private var closing = false
    private var retained = false

    // The retirement request that currently excludes this parent, or null. Only that request
    // may release the exclusion, so a late failure of an older request cannot undo a newer one.
    private var exclusion: RetirementRequest? = null
    private var retired = false

    @Synchronized
    fun eligible(): Boolean = !closing && exclusion == null && !connection.closeFuture().isDone

    @Synchronized
    fun retain(): Boolean {
        if (!eligible()) return false
        retained = true
        return true
    }

    /** Returns the request that now owns the exclusion, or null when retirement is denied. */
    @Synchronized
    fun retireAtAcceptor(): RetirementRequest? {
        // A parent already excluded by another approval (pending or delivered) is never approved again.
        if (retained || exclusion != null) return null
        return exclude()
    }

    private fun exclude(): RetirementRequest {
        val request = RetirementRequest()
        exclusion = request
        retired = true
        return request
    }

    @Synchronized
    fun restoreAfterFailedRetirement(request: RetirementRequest) {
        // Only the request that established the exclusion may release it.
        // Explicit close has its own terminal marker and is never undone here.
        if (exclusion !== request) return
        exclusion = null
        retired = false
    }

    @Synchronized
    fun wasRetired(): Boolean = retired

    @Synchronized
    fun closeRequested() {
        closing = true
    }

    fun reserve(): CompletableFuture<Boolean> {
        return CompletableFuture.completedFuture(retain())
    }

    fun rejectDuplicate() {
        // Only the physical initiator may retire a duplicate, after the acceptor confirms
        // that it has not retained this parent.
        if (!connection.isInitiator) return
        val request = synchronized(this) {
            if (retained || !eligible()) return
            exclude()
        }
        requestRetirement().whenComplete { accepted, failure ->
            if (failure != null || accepted != true) {
                // No remote authorization: this may be a published parent at the peer.
                restoreAfterFailedRetirement(request)
            }
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

    private fun requestRetirement(): CompletableFuture<Boolean> {
        val promise = try {
            session.createStream(SelectionBinding())
        } catch (failure: Exception) {
            return completedExceptionally(failure)
        }
        promise.controller.whenComplete { _, _ ->
            promise.stream.thenAccept { it.close() }
        }
        return promise.controller
    }

    override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
        if (retain()) return session.createStream(protocols)

        val closed = ConnectionClosedException("Cannot open a stream because the connection is closed or excluded from selection: $connection")
        return StreamPromise(
            completedExceptionally<Stream>(closed),
            completedExceptionally<T>(closed)
        )
    }
}

/** Identity of one retirement request; compared by reference only. */
internal class RetirementRequest

internal fun connectionEligible(connection: Connection): Boolean {
    if (connection.closeFuture().isDone) return false
    val netty = connection as? ConnectionOverNetty ?: return true
    // An application close requested before the selection state existed still excludes reuse.
    return !netty.closeWasRequested() && netty.selection?.eligible() != false
}

internal fun duplicateRetired(connection: Connection): Boolean =
    (connection as? ConnectionOverNetty)?.selection?.wasRetired() == true

internal fun reserveConnection(connection: Connection): CompletableFuture<Boolean> =
    (connection as? ConnectionOverNetty)?.selection?.reserve()
        ?: CompletableFuture.completedFuture(!connection.closeFuture().isDone)

internal fun rejectDuplicate(connection: Connection) {
    // A custom transport without coordination cannot establish that its remote parent is unused.
    (connection as? ConnectionOverNetty)?.selection?.rejectDuplicate()
}

internal class SelectionBinding :
    StrictProtocolBinding<Boolean>(SELECTION_PROTOCOL, SelectionProtocol())

private class SelectionProtocol : ProtocolHandler<Boolean>(1, 1) {
    override fun onStartInitiator(stream: Stream): CompletableFuture<Boolean> {
        val response = CompletableFuture<Boolean>()
        stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
            override fun onActivated(stream: Stream) {
                stream.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(RETIRE)).whenComplete { _, failure ->
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
            override fun onReadClosed(stream: Stream) {
                response.completeExceptionally(ConnectionClosedException("Connection selection stream ended writing before its response: $stream"))
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
                val request = if (msg.readableBytes() == 1 && selection != null && !connection.isInitiator) {
                    if (msg.readUnsignedByte().toInt() == RETIRE) selection.retireAtAcceptor() else null
                } else {
                    null
                }
                stream.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(if (request != null) 1 else 0))
                    .whenComplete { _, failure ->
                        if (failure != null) {
                            if (request != null) selection?.restoreAfterFailedRetirement(request)
                            selectionLogger.warn("Connection selection response failed on {}", connection, failure)
                        }
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
