package io.libp2p.multistream

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.Stream
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import java.time.Duration
import java.util.concurrent.CompletableFuture

class MultistreamImpl<TController>(
    override val bindings: List<ProtocolBinding<TController>>,
    val preHandler: P2PChannelHandler<*>? = null,
    val postHandler: P2PChannelHandler<*>? = null,
    val negotiationTimeLimit: Duration = DEFAULT_NEGOTIATION_TIME_LIMIT
) : Multistream<TController> {

    override fun initChannel(ch: P2PChannel): CompletableFuture<TController> {
        return with(ch) {
            val protocolSelect = ProtocolSelect(bindings)
            val selectedFuture = protocolSelect.selectedFuture
            // Registration can defer handlerAdded until after these initialization callbacks return.
            // Observe closure independently of handlers that teardown may remove before adding them.
            closeFuture().thenRun { settleClosedNegotiation(ch, selectedFuture) }
            if (settleClosedNegotiation(ch, protocolSelect.selectedFuture)) return protocolSelect.selectedFuture
            preHandler?.also {
                it.initChannel(ch)
            }
            if (settleClosedNegotiation(ch, protocolSelect.selectedFuture)) return protocolSelect.selectedFuture
            pushHandler(
                if (ch.isInitiator) {
                    Negotiator.createRequesterInitializer(
                        negotiationTimeLimit,
                        *bindings.flatMap { it.protocolDescriptor.announceProtocols }.toTypedArray()
                    )
                } else {
                    Negotiator.createResponderInitializer(
                        negotiationTimeLimit,
                        bindings.map { it.protocolDescriptor.protocolMatcher }
                    )
                }
            )
            postHandler?.also {
                it.initChannel(ch)
            }
            if (settleClosedNegotiation(ch, protocolSelect.selectedFuture)) return protocolSelect.selectedFuture
            pushHandler(protocolSelect)
            protocolSelect.selectedFuture
        }
    }
}

// A visitor can close a stream before negotiation handlers exist to observe its close event.
private fun <T> settleClosedNegotiation(channel: P2PChannel, controller: CompletableFuture<T>): Boolean {
    if (!channel.closeFuture().isDone) return false
    val failure = ConnectionClosedException("Channel closed before protocol negotiation: $channel")
    (channel as? Stream)?.getProtocol()?.completeExceptionally(failure)
    controller.completeExceptionally(failure)
    return true
}
