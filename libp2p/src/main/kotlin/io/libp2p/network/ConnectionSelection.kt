package io.libp2p.network

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.ConnectionHold
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
import java.util.concurrent.atomic.AtomicBoolean

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
    private var provisionalUses = 0L
    private var duplicate = false
    private val pendingRetirements = mutableSetOf<CompletableFuture<RetirementRequest?>>()

    init {
        connection.closeFuture().thenRun { closeRequested() }
    }

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
    fun retireAtAcceptor(): CompletableFuture<RetirementRequest?> {
        val result = CompletableFuture<RetirementRequest?>()
        val approval = synchronized(this) {
            when {
                retained || !eligible() -> true to null
                provisionalUses != 0L -> {
                    pendingRetirements.add(result)
                    false to null
                }
                else -> true to exclude()
            }
        }
        result.whenComplete { _, _ -> synchronized(this) { pendingRetirements.remove(result) } }
        if (approval.first) publishApproval(result, approval.second)
        return result
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

    fun closeRequested() {
        val pending = synchronized(this) {
            closing = true
            pendingRetirements.toList().also { pendingRetirements.clear() }
        }
        pending.forEach { it.complete(null) }
    }

    fun reserve(): CompletableFuture<Boolean> {
        return CompletableFuture.completedFuture(retain())
    }

    fun rejectDuplicate() {
        // Only the physical initiator may retire a duplicate, after the acceptor confirms
        // that it has not retained this parent.
        if (!connection.isInitiator) return
        val request = synchronized(this) {
            duplicate = true
            if (retained || provisionalUses != 0L || !eligible()) return
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

    @Synchronized
    private fun acquireUse(): ProvisionalUse? {
        if (!eligible()) return null
        provisionalUses++
        return ProvisionalUse { releaseUse() }
    }

    private fun releaseUse() {
        val approvals = synchronized(this) {
            provisionalUses--
            if (provisionalUses == 0L) {
                pendingRetirements.map { future ->
                    future to if (!future.isDone && !retained && eligible()) exclude() else null
                }.also { pendingRetirements.clear() }
            } else emptyList()
        }
        approvals.forEach { (future, request) -> publishApproval(future, request) }
        if (synchronized(this) { duplicate }) rejectDuplicate()
    }

    private fun publishApproval(future: CompletableFuture<RetirementRequest?>, request: RetirementRequest?) {
        if (!future.complete(request) && request != null) restoreAfterFailedRetirement(request)
    }

    /** Incoming negotiation and retirement share the same admission ordering. */
    fun observeIncomingStream(stream: Stream): (String) -> Boolean {
        val use = acquireUse()
        stream.closeFuture().whenComplete { _, _ -> use?.close() }
        return { protocol ->
            if (protocol == SELECTION_PROTOCOL) {
                use?.close()
                true
            } else use != null && eligible()
        }
    }

    fun hold(): ConnectionHold? {
        val use = acquireUse() ?: return null
        return object : ConnectionHold {
            override val connection: Connection get() = this@ConnectionSelection.connection

            override fun promote(): Boolean = synchronized(this@ConnectionSelection) {
                !use.released.get() && retain()
            }

            override fun close() = use.close()

            override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
                val childUse = synchronized(this@ConnectionSelection) {
                    if (use.released.get()) null else acquireUse()
                } ?: return failedProvisionalStream(connection)
                val source = try {
                    session.createStream(protocols)
                } catch (failure: Throwable) {
                    childUse.close()
                    close()
                    throw failure
                }
                val streamResult = CompletableFuture<Stream>()
                val controllerResult = CompletableFuture<T>()
                val abandoned = AtomicBoolean()
                val abandon = {
                    if (abandoned.compareAndSet(false, true)) {
                        source.stream.thenAccept { stream ->
                            stream.close().whenComplete { _, failure ->
                                if (failure != null) selectionLogger.warn("Provisional child close failed on {}", stream, failure)
                            }
                        }
                        source.controller.cancel(false)
                        close()
                    }
                }
                streamResult.whenComplete { _, failure -> if (failure != null) abandon() }
                controllerResult.whenComplete { _, failure -> if (failure != null) abandon() }
                source.stream.whenComplete { stream, failure ->
                    if (failure != null) {
                        childUse.close()
                        streamResult.completeExceptionally(failure)
                    } else {
                        stream.closeFuture().whenComplete { _, _ -> childUse.close() }
                        if (eligible()) streamResult.complete(stream)
                        else streamResult.completeExceptionally(provisionalStreamFailure(connection))
                    }
                }
                source.controller.whenComplete { value, failure ->
                    when {
                        failure != null -> controllerResult.completeExceptionally(failure)
                        !eligible() -> controllerResult.completeExceptionally(provisionalStreamFailure(connection))
                        else -> controllerResult.complete(value)
                    }
                }
                return StreamPromise(streamResult, controllerResult)
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

private class ProvisionalUse(private val release: () -> Unit) {
    val released = AtomicBoolean()
    fun close() {
        if (released.compareAndSet(false, true)) release()
    }
}

private fun provisionalStreamFailure(connection: Connection) = ConnectionClosedException(
    "Cannot open a provisional stream because its hold was released or its connection is closed or excluded from selection: $connection"
)

private fun <T> failedProvisionalStream(connection: Connection): StreamPromise<T> {
    val failure = provisionalStreamFailure(connection)
    return StreamPromise(completedExceptionally<Stream>(failure), completedExceptionally<T>(failure))
}

private fun connectionSelection(connection: Connection): ConnectionSelection? =
    if (connection is ConnectionOverNetty) {
        // Before mux setup, the Netty parent already records explicit close requests.
        connection.selection
    } else {
        // Transparent decorators forward the identity of the session that admits their streams.
        connection.muxerSession().streamAdmissionSession() as? ConnectionSelection
    }

internal fun connectionEligible(connection: Connection): Boolean {
    if (connection.closeFuture().isDone) return false
    if (connection is ConnectionOverNetty && connection.closeWasRequested()) return false
    return connectionSelection(connection)?.eligible() != false
}

internal fun duplicateRetired(connection: Connection): Boolean =
    connectionSelection(connection)?.wasRetired() == true

internal fun holdConnectionLocally(connection: Connection): ConnectionHold? =
    if (connectionEligible(connection)) connectionSelection(connection)?.hold() else null

internal fun reserveConnection(connection: Connection): CompletableFuture<Boolean> =
    if (!connectionEligible(connection)) {
        CompletableFuture.completedFuture(false)
    } else {
        connectionSelection(connection)?.reserve() ?: CompletableFuture.completedFuture(true)
    }

internal fun rejectDuplicate(connection: Connection) {
    // A custom transport without coordination cannot establish that its remote parent is unused.
    connectionSelection(connection)?.rejectDuplicate()
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
                val approval = if (msg.readableBytes() == 1 && selection != null && !connection.isInitiator &&
                    msg.readUnsignedByte().toInt() == RETIRE
                ) selection.retireAtAcceptor() else CompletableFuture.completedFuture(null)
                stream.closeFuture().whenComplete { _, _ -> approval.cancel(false) }
                approval.whenComplete { request, approvalFailure ->
                    if (approvalFailure != null) return@whenComplete
                    stream.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(if (request != null) 1 else 0))
                    .whenComplete { _, failure ->
                        if (failure != null) {
                            if (request != null) selection?.restoreAfterFailedRetirement(request)
                            selectionLogger.warn("Connection selection response failed on {}", connection, failure)
                        }
                        stream.closeWrite()
                    }
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
