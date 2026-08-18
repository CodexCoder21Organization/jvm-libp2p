package io.libp2p.multistream

import io.libp2p.etc.types.seconds
import io.libp2p.tools.Echo
import io.libp2p.tools.TestStreamChannel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * A caller of Host.newStream waits on the controller future. If the stream dies before its
 * multistream-select negotiation finishes, that future must complete exceptionally, otherwise the
 * caller waits out its entire timeout for a stream it could already know is gone.
 */
class B2ControllerOnEarlyCloseTest {

    @Test
    @Timeout(10)
    fun controllerCompletesWhenStreamClosesBeforeNegotiationFinishes() {
        val channel = TestStreamChannel(
            true,
            Echo(),
            multistreamProtocol = MultistreamProtocolDebugV1(10.seconds)
        )
        Assertions.assertFalse(
            channel.controllerFuture.isDone,
            "Negotiation has not completed yet, so the controller must still be pending"
        )

        channel.close().sync()

        Assertions.assertTrue(
            channel.controllerFuture.isDone,
            "The stream closed before negotiation completed, but the controller future returned to the " +
                "caller was left pending. A caller therefore blocks for its whole timeout instead of " +
                "learning immediately that the stream is gone."
        )
    }
}
