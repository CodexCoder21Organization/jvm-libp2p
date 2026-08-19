@file:Suppress("DEPRECATION")

package io.libp2p.mux.yamux

import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.StreamHandler
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.multistream.MultistreamProtocolDebugV1
import io.libp2p.etc.types.seconds
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.security.SecureChannel
import io.libp2p.tools.NullTransport
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A caller of `Host.newStream` is handed a `StreamPromise` and waits on its controller. The only thing that
 * can complete that controller once the substream exists is the substream's multistream-select negotiation
 * finishing — or the substream dying. If a substream dies without completing its controller, the caller has
 * no way to learn the stream is gone and waits out its entire timeout on it.
 *
 * These tests pin that contract on a REAL `NioEventLoopGroup` over a real TCP connection. An
 * `EmbeddedChannel` runs every deferred task inline, so it cannot observe the ordering that matters here —
 * the same caveat `AbstractChildChannelCloseTest` records for the close path it covers.
 */
class YamuxNegotiationControllerOnStreamCloseTest {

    private class UnusedController

    private object NeverAnsweredProtocol : ProtocolBinding<UnusedController> {
        override val protocolDescriptor = ProtocolDescriptor("/negotiation-controller-probe/1.0.0")
        override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<UnusedController> =
            CompletableFuture.completedFuture(UnusedController())
    }

    @Test
    @Timeout(60)
    fun `controller completes when the substream is closed locally before negotiation finishes`() {
        Harness().use { harness ->
            val promise = harness.handler.createStream(listOf(NeverAnsweredProtocol))
            val stream = promise.stream.get(5, TimeUnit.SECONDS)
            assertThat(promise.controller.isDone)
                .withFailMessage("The remote never answers, so negotiation cannot have completed")
                .isFalse()

            stream.close().get(5, TimeUnit.SECONDS)

            assertThat(awaitDone(promise.controller))
                .withFailMessage(
                    "The substream closed before its negotiation completed, but the controller future handed " +
                        "to the caller was left pending. A caller blocks for its whole timeout on a stream it " +
                        "could already know is gone."
                )
                .isTrue()
        }
    }

    @Test
    @Timeout(60)
    fun `controller completes when the connection dies before negotiation finishes`() {
        Harness().use { harness ->
            val promise = harness.handler.createStream(listOf(NeverAnsweredProtocol))
            promise.stream.get(5, TimeUnit.SECONDS)
            assertThat(promise.controller.isDone)
                .withFailMessage("The remote never answers, so negotiation cannot have completed")
                .isFalse()

            harness.client.close().await(5, TimeUnit.SECONDS)

            assertThat(awaitDone(promise.controller))
                .withFailMessage(
                    "The connection carrying this substream closed before negotiation completed, but the " +
                        "controller future handed to the caller was left pending."
                )
                .isTrue()
        }
    }

    /**
     * The production shape, with the ordering that matters forced deterministically.
     *
     * A Yamux connection latches a terminal write failure and schedules its own close as a separate
     * event-loop task. If a substream is closed in the window BEFORE that scheduled close runs, the
     * substream's own close emits an RST frame, that write throws the latched failure, and
     * `AbstractChildChannel.doClose()` unwinds before reaching `pipeline().fireChannelUnregistered()`.
     * Netty's `doClose0` completes the channel's close future anyway and latches `closeInitiated`, so the
     * substream reports itself closed, the later connection close is a no-op for it, and its pipeline is
     * never unregistered. `ProtocolSelect` fails its controller only from `channelUnregistered`, so the
     * controller handed to the caller is orphaned forever.
     *
     * Both steps run inside ONE event-loop task, so the scheduled connection close cannot interleave. No
     * iteration counts, no sleeps, no dependence on how fast the machine is.
     */
    @Test
    @Timeout(60)
    fun `controller completes when the substream close races the connection's own failure close`() {
        Harness(maxBufferedConnectionWrites = 4096).use { harness ->
            val promise = harness.handler.createStream(listOf(NeverAnsweredProtocol))
            val stream = promise.stream.get(5, TimeUnit.SECONDS)
            val victim = harness.handler.createStream(listOf(NeverAnsweredProtocol))
                .stream.get(5, TimeUnit.SECONDS)
            assertThat(promise.controller.isDone)
                .withFailMessage("The remote never answers, so negotiation cannot have completed")
                .isFalse()

            val outcome = harness.onClientEventLoop {
                val writeOutcome = runCatching {
                    victim.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(ByteArray(8 * 1024)))
                }
                val closeOutcome = runCatching { stream.close() }
                "write=" + writeOutcome.exceptionOrNull()?.javaClass?.simpleName +
                    " close=" + closeOutcome.exceptionOrNull()?.javaClass?.simpleName +
                    " controllerDoneImmediately=" + promise.controller.isDone
            }
            println("PROBE-INJECTION " + outcome)

            assertThat(awaitDone(promise.controller))
                .withFailMessage(
                    "The substream's close path failed before it could tear its pipeline down, so the " +
                        "controller future handed to the caller was never completed - neither normally nor " +
                        "exceptionally - and the later connection close is a no-op for an already-closed " +
                        "channel. The caller gets no signal at all and blocks for its whole timeout. The " +
                        "controller must be failed from the channel's close future, which Netty completes " +
                        "unconditionally, exactly as TotalTimeoutHandler already does for its timeout task."
                )
                .isTrue()
        }
    }

    @Test
    @Timeout(60)
    fun `controller completes when the remote drops the connection before negotiation finishes`() {
        Harness().use { harness ->
            val promise = harness.handler.createStream(listOf(NeverAnsweredProtocol))
            promise.stream.get(5, TimeUnit.SECONDS)
            assertThat(promise.controller.isDone)
                .withFailMessage("The remote never answers, so negotiation cannot have completed")
                .isFalse()

            harness.closeConnectionRemotely()
            assertThat(harness.client.closeFuture().await(10, TimeUnit.SECONDS))
                .withFailMessage("The local end must observe the remote close")
                .isTrue()

            assertThat(awaitDone(promise.controller))
                .withFailMessage(
                    "The remote dropped the connection carrying this substream before negotiation completed, " +
                        "but the controller future handed to the caller was left pending."
                )
                .isTrue()
        }
    }


    /**
     * The production shape: the substream dies because the CONNECTION closed, and the question is not only
     * whether the controller completes but whether the substream's pipeline is torn down at all. A closed
     * substream that keeps its handlers keeps everything they retain, and anything that learns of the
     * channel's death through the pipeline is never told.
     */
    @Test
    @Timeout(60)
    fun `a substream whose connection closes has its pipeline torn down`() {
        Harness().use { harness ->
            val promise = harness.handler.createStream(listOf(NeverAnsweredProtocol))
            val stream = promise.stream.get(5, TimeUnit.SECONDS)
            val channel = (stream as io.libp2p.transport.implementation.P2PChannelOverNetty).nettyChannel
            assertThat(channel.pipeline().names())
                .withFailMessage("the negotiation pipeline should be in place before the connection dies")
                .contains("ProtocolSelect#0")

            harness.closeConnectionRemotely()
            assertThat(harness.client.closeFuture().await(10, TimeUnit.SECONDS)).isTrue()
            assertThat(channel.closeFuture().await(10, TimeUnit.SECONDS))
                .withFailMessage("the substream must close when its connection does")
                .isTrue()

            val remaining = channel.pipeline().names().filterNot { it.contains("TailContext") }
            assertThat(remaining)
                .withFailMessage(
                    "The substream reported itself closed but kept its whole pipeline: %s. Nothing fires " +
                        "handlerRemoved or channelUnregistered on those handlers again, so everything they " +
                        "retain stays retained and anything waiting to hear the channel died never does.",
                    remaining
                )
                .isEmpty()
        }
    }

    private fun awaitDone(future: CompletableFuture<*>): Boolean {
        // Five seconds is the budget the real caller had. Completing later than this is not "completing".
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (future.isDone) return true
            Thread.sleep(20)
        }
        return future.isDone
    }

    private class Harness(
        maxBufferedConnectionWrites: Int = 10 * 1024 * 1024,
        maxFrameDataLength: Int = 1024 * 1024
    ) : AutoCloseable {
        private val group: EventLoopGroup = NioEventLoopGroup(1)
        private val localPeerId = PeerId.random()
        private val remoteKey = generateKeyPair(KeyType.ECDSA).second
        private val remotePeerId = PeerId.fromPubKey(remoteKey)
        // A negotiation time limit far above the assertion window, so the multistream timeout handler
        // cannot rescue an orphaned controller and make the contract look satisfied.
        val handler = YamuxHandler(
            MultistreamProtocolDebugV1(120.seconds),
            maxFrameDataLength,
            null,
            StreamHandler<Unit> { CompletableFuture.completedFuture(Unit) },
            true,
            maxBufferedConnectionWrites,
            DEFAULT_ACK_BACKLOG_LIMIT,
            INITIAL_WINDOW_SIZE
        )
        val server: Channel
        val client: Channel
        val acceptedRemote = CompletableFuture<Channel>()

        /** Closes the connection from the REMOTE end, which is how it dies in production. */
        fun closeConnectionRemotely() {
            acceptedRemote.get(5, TimeUnit.SECONDS).close().await(5, TimeUnit.SECONDS)
        }

        init {
            server = ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            ch.config().isAutoRead = false
                            acceptedRemote.complete(ch)
                        }
                    }
                )
                .bind(InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel()

            client = Bootstrap()
                .group(group)
                .channel(NioSocketChannel::class.java)
                .handler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            val connection = ConnectionOverNetty(ch, NullTransport(), true)
                            connection.setSecureSession(
                                SecureChannel.Session(localPeerId, remotePeerId, remoteKey, null)
                            )
                            ch.pipeline().addLast(YamuxFrameCodec(maxFrameDataLength))
                            ch.pipeline().addLast(handler)
                        }
                    }
                )
                .connect(server.localAddress())
                .sync()
                .channel()
        }

        fun <T> onClientEventLoop(block: () -> T): T =
            client.eventLoop().submit<T> { block() }.get(10, TimeUnit.SECONDS)

        override fun close() {
            runCatching { client.close().await(5, TimeUnit.SECONDS) }
            runCatching { server.close().await(5, TimeUnit.SECONDS) }
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).get(10, TimeUnit.SECONDS)
        }
    }
}
