package io.libp2p.mux.yamux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.P2PChannel
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.netty.channel.ChannelHandlerContext
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CompletableFuture

/**
 * A caller of `Host.newStream` waits on the controller future the returned StreamPromise carries. When the
 * substream dies before its multistream-select negotiation completes, that future must complete — otherwise
 * the caller sits out its entire timeout waiting for a stream that is already gone.
 */
class B2ControllerOnMuxStreamEarlyCloseTest : MuxHandlerAbstractTest() {

    override val maxFrameDataLength = 256
    private fun idIterator(initiator: Boolean) = iterator {
        val generator = YamuxStreamIdGenerator(initiator)
        while (true) yield(generator.next())
    }
    override val localMuxIdGenerator = idIterator(isLocalConnectionInitiator)
    override val remoteMuxIdGenerator = idIterator(!isLocalConnectionInitiator)

    private class DummyController

    private object DummyBinding : ProtocolBinding<DummyController> {
        override val protocolDescriptor = ProtocolDescriptor("/b2-probe/1.0.0")
        override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<DummyController> =
            CompletableFuture.completedFuture(DummyController())
    }

    override fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler =
        object : YamuxHandler(
            MultistreamProtocolV1,
            maxFrameDataLength,
            null,
            streamHandler,
            true,
            512,
            42,
            300
        ) {
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

    override fun writeFrame(frame: AbstractTestMuxFrame) = throw UnsupportedOperationException()
    override fun readFrame(): AbstractTestMuxFrame? = null

    @Test
    @Timeout(15)
    fun controllerCompletesWhenConnectionDiesBeforeNegotiationFinishes() {
        val promise = multistreamHandler.createStream(listOf(DummyBinding))
        ech.runPendingTasks()

        Assertions.assertTrue(
            promise.stream.isDone,
            "The substream itself should have been created"
        )
        Assertions.assertFalse(
            promise.controller.isDone,
            "Nothing has answered the protocol proposal yet, so the controller must still be pending"
        )

        ech.close().sync()
        ech.runPendingTasks()

        Assertions.assertTrue(
            promise.controller.isDone,
            "The connection carrying this substream closed before negotiation completed, but the controller " +
                "future handed to the caller was left pending. A caller therefore blocks for its whole " +
                "timeout instead of learning immediately that the stream is gone."
        )
    }

    override fun cleanUpAndCheck() {}
}
