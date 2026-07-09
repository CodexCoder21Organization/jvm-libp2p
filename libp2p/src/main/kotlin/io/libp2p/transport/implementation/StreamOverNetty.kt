package io.libp2p.transport.implementation

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.WRITE_FAILURE
import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.channel.Channel
import java.util.concurrent.CompletableFuture

open class StreamOverNetty(
    ch: Channel,
    override val connection: Connection,
    initiator: Boolean
) : Stream, P2PChannelOverNetty(ch, initiator) {
    init {
        nettyChannel.attr(PROTOCOL).set(CompletableFuture())
    }

    /**
     * Returns the [PeerId] of the remote peer [Connection] which this
     * [Stream] created on
     */
    override fun remotePeerId() = connection.secureSession().remoteId

    /**
     * @return negotiated protocol
     */
    override fun getProtocol(): CompletableFuture<String> = nettyChannel.attr(PROTOCOL).get()

    override fun writeAndFlush(msg: Any) {
        writeAndFlushWithFuture(msg)
    }

    override fun writeAndFlushWithFuture(msg: Any): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        nettyChannel.writeAndFlush(msg).addListener {
            if (it.isSuccess) {
                result.complete(Unit)
            } else {
                result.completeExceptionally(
                    connectionWriteFailure()
                        ?: it.cause()
                        ?: ConnectionClosedException("Stream write failed without a reported cause: $nettyChannel")
                )
            }
        }
        return result
    }

    private fun connectionWriteFailure(): Throwable? {
        return (connection as? ConnectionOverNetty)?.nettyChannel?.attr(WRITE_FAILURE)?.get()
    }

    override fun closeWrite(): CompletableFuture<Unit> {
        return nettyChannel.disconnect().toVoidCompletableFuture()
    }
}
