package io.libp2p.protocol

import io.libp2p.core.Stream

interface ProtocolMessageHandler<TMessage> {
    fun onActivated(stream: Stream) = Unit

    /**
     * Receives a borrowed message. The adapter releases its reference after this callback returns,
     * including when the callback throws or closes the stream. Retain a reference-counted message
     * before keeping it or passing it to a write, and release that retained reference when finished.
     */
    fun onMessage(stream: Stream, msg: TMessage) = Unit
    fun onClosed(stream: Stream) = Unit
    fun onReadClosed(stream: Stream) = Unit
    fun onException(cause: Throwable?) = Unit
}
