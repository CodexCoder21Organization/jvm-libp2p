package io.libp2p.mux.yamux

import io.libp2p.core.P2PChannel
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.etc.types.seconds
import io.libp2p.multistream.MultistreamProtocolDebugV1
import io.libp2p.tools.TestChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class YamuxSelectedProtocolControllerOnCloseTest {

    private class PendingController

    @Test
    fun `controller fails when stream closes after protocol selection but before controller initialization`() {
        val protocolInitialized = CompletableFuture<Unit>()
        val pendingController = CompletableFuture<PendingController>()
        val clientBinding = object : ProtocolBinding<PendingController> {
            override val protocolDescriptor = ProtocolDescriptor("/pending-controller/1.0.0")

            override fun initChannel(
                ch: P2PChannel,
                selectedProtocol: String
            ): CompletableFuture<PendingController> {
                protocolInitialized.complete(Unit)
                return pendingController
            }
        }
        val serverBinding = object : ProtocolBinding<Unit> {
            override val protocolDescriptor = ProtocolDescriptor("/pending-controller/1.0.0")

            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<Unit> =
                CompletableFuture.completedFuture(Unit)
        }
        val multistream = MultistreamProtocolDebugV1(120.seconds)
        val clientHandler = YamuxHandler(
            multistream,
            1024 * 1024,
            null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true,
            10 * 1024 * 1024,
            DEFAULT_ACK_BACKLOG_LIMIT,
            INITIAL_WINDOW_SIZE
        )
        val serverHandler = YamuxHandler(
            multistream,
            1024 * 1024,
            null,
            multistream.createMultistream(listOf(serverBinding)).toStreamHandler(),
            false,
            10 * 1024 * 1024,
            DEFAULT_ACK_BACKLOG_LIMIT,
            INITIAL_WINDOW_SIZE
        )
        val client = TestChannel(
            "selected-protocol-close-client",
            true,
            YamuxFrameCodec(1024 * 1024),
            clientHandler
        )
        val server = TestChannel(
            "selected-protocol-close-server",
            false,
            YamuxFrameCodec(1024 * 1024),
            serverHandler
        )

        try {
            val promise = clientHandler.createStream(listOf(clientBinding))

            transferAll(client, server)
            transferAll(server, client)

            protocolInitialized.get(5, TimeUnit.SECONDS)
            val stream = promise.stream.get(5, TimeUnit.SECONDS)
            assertThat(promise.controller.isDone)
                .withFailMessage("the selected protocol controller must still be pending before the stream closes")
                .isFalse()

            stream.close().get(5, TimeUnit.SECONDS)

            assertThat(promise.controller.isCompletedExceptionally)
                .withFailMessage(
                    "The stream closed after protocol selection while its controller was still initializing, " +
                        "but the controller future was left pending. The caller would wait for its whole " +
                        "30-second budget on a stream that is already closed."
                )
                .isTrue()
        } finally {
            client.finishAndReleaseAll()
            server.finishAndReleaseAll()
        }
    }

    private fun transferAll(from: TestChannel, to: TestChannel) {
        from.runPendingTasks()
        while (true) {
            val message = from.readOutbound<Any>() ?: break
            to.writeInbound(message)
            to.runPendingTasks()
        }
    }
}
