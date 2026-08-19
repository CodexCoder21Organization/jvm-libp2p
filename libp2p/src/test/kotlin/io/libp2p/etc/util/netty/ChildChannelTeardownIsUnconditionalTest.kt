package io.libp2p.etc.util.netty

import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.embedded.EmbeddedChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Closing a child channel must tear its pipeline down **unconditionally** — including when the work that
 * runs earlier in `doClose()` fails.
 *
 * Netty's `AbstractUnsafe.doClose0` completes the channel's close future even when `doClose()` throws, so a
 * child channel that fails partway through its close still reports itself closed while keeping its entire
 * pipeline attached. Nothing ever fires `handlerRemoved` or `channelUnregistered` on those handlers again,
 * because every later `close()` is a no-op once `closeInitiated` is latched.
 *
 * Two production consequences of that one gap, both observed:
 *
 *  - **A caller of `Host.newStream` hangs for its whole timeout.** `ProtocolSelect` fails the controller
 *    future it hands the caller only from `channelUnregistered`. UrlResolver buildtest run 21bb281d caught
 *    a substream that opened at 21 ms, closed at 28 ms, and whose controller was still not completed at
 *    5016 ms — with the channel reporting `open=false closeFutureDone=true` while its pipeline still held
 *    `[PendingWriteAccountingHandler, TotalTimeoutHandler, LimitedProtobufVarint32FrameDecoder,
 *    ProtobufVarint32LengthFieldPrepender, StringDecoder, StringEncoder, StringSuffixCodec,
 *    Negotiator$RequesterHandler, ProtocolSelect, TailContext]`.
 *  - **Closed channels are retained with their whole pipeline**, which is the heap signature
 *    [AbstractChildChannelCloseTest] documents. That test made the teardown synchronous; this one makes it
 *    unconditional.
 *
 * A yamux substream reaches this state by an ordinary route: `MuxChannel.onClientClosed()` asks the muxer to
 * emit an RST frame, and that write throws once the connection has latched a terminal write failure.
 */
class ChildChannelTeardownIsUnconditionalTest {

    private class FailingCloseChildChannel(
        parent: io.netty.channel.Channel,
        private val failure: RuntimeException
    ) : AbstractChildChannel(parent, null) {
        override fun localAddress0() = null
        override fun remoteAddress0() = null
        override fun doWrite(buf: ChannelOutboundBuffer) {
            while (buf.current() != null) buf.remove()
        }
        override fun metadata(): ChannelMetadata = ChannelMetadata(false)

        /** Stands in for the muxer's RST write, which throws on a connection with a latched write failure. */
        override fun onClientClosed() = throw failure
    }

    @Suppress("DEPRECATION")
    private val nio = io.netty.channel.nio.NioEventLoopGroup(1)
    private val parent = EmbeddedChannel()

    @AfterEach
    fun teardown() {
        parent.close().sync()
        nio.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync()
    }

    @Test
    @Timeout(30)
    fun `a child channel whose close fails partway still tears its pipeline down`() {
        val child = FailingCloseChildChannel(parent, RuntimeException("terminal write failure on the parent"))
        nio.next().register(child).sync()

        val handlerRemoved = java.util.concurrent.atomic.AtomicBoolean(false)
        val unregistered = java.util.concurrent.atomic.AtomicBoolean(false)
        nio.next().submit {
            child.pipeline().addLast(
                "observer",
                object : io.netty.channel.ChannelInboundHandlerAdapter() {
                    override fun handlerRemoved(ctx: io.netty.channel.ChannelHandlerContext) {
                        handlerRemoved.set(true)
                    }
                    override fun channelUnregistered(ctx: io.netty.channel.ChannelHandlerContext) {
                        unregistered.set(true)
                        ctx.fireChannelUnregistered()
                    }
                }
            )
        }.sync()

        // Close on the event loop. The close is expected to report the failure; what matters is the state
        // it leaves behind, so the outcome of close() itself is deliberately not asserted.
        runCatching { nio.next().submit { child.close() }.get(10, TimeUnit.SECONDS) }

        assertThat(child.closeFuture().await(10, TimeUnit.SECONDS))
            .withFailMessage("Netty completes the close future even when doClose() throws")
            .isTrue()

        assertThat(unregistered.get())
            .withFailMessage(
                "The child channel reports itself closed but channelUnregistered never reached its " +
                    "pipeline, because doClose() unwound before firing it. Anything that learns of the " +
                    "channel's death that way — ProtocolSelect failing the controller a newStream caller " +
                    "is blocked on, for one — is never told, and waits out its entire timeout."
            )
            .isTrue()

        assertThat(handlerRemoved.get())
            .withFailMessage(
                "The closed child channel kept its pipeline: handlerRemoved never fired, so every handler " +
                    "(and anything they retain, such as a scheduled TotalTimeoutHandler task) stays " +
                    "attached to a channel that is already closed."
            )
            .isTrue()

        assertThat(child.pipeline().names())
            .withFailMessage("A closed child channel must not retain application handlers")
            .doesNotContain("observer")
    }
}
