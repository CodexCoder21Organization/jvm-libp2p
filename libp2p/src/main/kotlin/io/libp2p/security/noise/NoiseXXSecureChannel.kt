package io.libp2p.security.noise

import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString
import com.southernstorm.noise.protocol.DHState
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.marshalPublicKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.crypto.nonBlockingSecureRandom
import io.libp2p.etc.REMOTE_PEER_ID
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toUShortBigEndian
import io.libp2p.etc.types.uShortToBytesBigEndian
import io.libp2p.etc.util.netty.SplitEncoder
import io.libp2p.security.InvalidRemotePubKey
import io.libp2p.security.SecureHandshakeError
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.CombinedChannelDuplexHandler
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.util.concurrent.FastThreadLocal
import io.netty.util.concurrent.SingleThreadEventExecutor
import org.slf4j.LoggerFactory
import spipe.pb.Spipe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.BiConsumer

enum class Role(val intVal: Int) { INIT(HandshakeState.INITIATOR), RESP(HandshakeState.RESPONDER) }

private val log = LoggerFactory.getLogger(NoiseXXSecureChannel::class.java)
const val HandshakeNettyHandlerName = "HandshakeNettyHandler"
const val HandshakeReadTimeoutNettyHandlerName = "HandshakeReadTimeoutNettyHandler"
const val NoiseCodeNettyHandlerName = "NoiseXXCodec"
const val MaxCipheredPacketLength = 65535
const val HandshakeTimeoutSec = 5

class UShortLengthCodec : CombinedChannelDuplexHandler<LengthFieldBasedFrameDecoder, LengthFieldPrepender>(
    LengthFieldBasedFrameDecoder(MaxCipheredPacketLength + 2, 0, 2, 0, 2),
    LengthFieldPrepender(2)
) {
    internal companion object Factory {
        fun create() = UShortLengthCodec()
    }
}

private class NoiseHandshakeReadTimeoutHandler(
    private val armInitialReadWhenAdded: Boolean
) : ChannelInboundHandlerAdapter() {
    private val generation = AtomicLong()
    private val waitingForRemote = AtomicBoolean()

    @Volatile
    private var handlerContext: ChannelHandlerContext? = null
    private var timeoutFuture: ScheduledFuture<*>? = null

    fun arm() {
        val ctx = handlerContext ?: return
        val armedGeneration = generation.incrementAndGet()
        waitingForRemote.set(true)
        val scheduleTimeout = arms.create(this, ctx, armedGeneration)
        if (ctx.executor().inEventLoop()) {
            scheduleTimeout.run()
        } else {
            ctx.executor().execute(scheduleTimeout)
        }
    }

    private fun armOnLoop(ctx: ChannelHandlerContext, armedGeneration: Long) {
        if (!waitingForRemote.get() || generation.get() != armedGeneration) return
        timeoutFuture?.cancel(false)
        timeoutFuture = ctx.executor().schedule(
            expirations.create(this, ctx, armedGeneration),
            HandshakeTimeoutSec.toLong(),
            TimeUnit.SECONDS
        )
    }

    private class Arm(
        val owner: NoiseHandshakeReadTimeoutHandler,
        val ctx: ChannelHandlerContext,
        val generation: Long
    ) : Runnable {
        override fun run() = owner.armOnLoop(ctx, generation)
        companion object Factory {
            fun create(owner: NoiseHandshakeReadTimeoutHandler, ctx: ChannelHandlerContext, generation: Long) =
                Arm(owner, ctx, generation)
        }
    }

    private class Expire(
        val owner: NoiseHandshakeReadTimeoutHandler,
        val ctx: ChannelHandlerContext,
        val generation: Long
    ) : Runnable {
        override fun run() {
            if (owner.waitingForRemote.get() && owner.generation.get() == generation) {
                ctx.fireExceptionCaught(ReadTimeoutException.INSTANCE)
            }
        }
        companion object Factory {
            fun create(owner: NoiseHandshakeReadTimeoutHandler, ctx: ChannelHandlerContext, generation: Long) =
                Expire(owner, ctx, generation)
        }
    }

    companion object Factory {
        private val arms = Arm
        private val expirations = Expire
        fun create(armInitialReadWhenAdded: Boolean) = NoiseHandshakeReadTimeoutHandler(armInitialReadWhenAdded)
    }

    fun disarm() {
        waitingForRemote.set(false)
        generation.incrementAndGet()
        timeoutFuture?.cancel(false)
        timeoutFuture = null
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        disarm()
        handlerContext = null
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        handlerContext = ctx
        // A responder waits for message 1 before it has any outbound write whose
        // completion could arm the next phase. Arm while this handler is being added,
        // before the handshake handler can receive an already-buffered message 1.
        if (armInitialReadWhenAdded) arm()
    }
}

/**
 * An immutable scheduling snapshot delivered immediately before computation, on its selected executor.
 * [pendingHandshakesOnLoop] is captured on the loop at admission and includes ready-publication callbacks.
 */
data class NoiseHandshakeComputation(
    val channelId: String,
    val onEventLoop: Boolean,
    val pendingHandshakesOnLoop: Int
) {
    internal companion object Factory {
        fun create(channelId: String, onEventLoop: Boolean, pendingHandshakesOnLoop: Int) =
            NoiseHandshakeComputation(channelId, onEventLoop, pendingHandshakesOnLoop)
    }
}

class NoiseXXSecureChannel @JvmOverloads constructor(
    private val localKey: PrivKey,
    // The observer is scoped to this secure channel, without process-wide hook state.
    private val computationObserver: ((NoiseHandshakeComputation) -> Unit)? = null
) :
    SecureChannel {

    @Suppress("UNUSED_PARAMETER")
    constructor(localKey: PrivKey, muxerProtocols: List<StreamMuxer>) : this(localKey)

    companion object {
        const val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"
        const val announce = "/noise"

        @JvmStatic
        var localStaticPrivateKey25519: ByteArray = ByteArray(32).also { nonBlockingSecureRandom().nextBytes(it) }

        // temporary flag to handle different Noise handshake treatments
        // if true the noise handshake payload is prepended with 2 bytes encoded length
        @JvmStatic
        var rustInteroperability = false
    }

    // This binding belongs to one host. Connections copy its prepared static key,
    // while every handshake still creates its own ephemeral key and state.
    private val preparedIdentity = prepareNoiseIdentity(localKey)

    // These are the factories used by each real connection, prepared before the host escapes.
    private val handshakeFactory = NoiseIoHandshake

    override val protocolDescriptor = ProtocolDescriptor(announce)

    override fun initChannel(
        ch: P2PChannel,
        selectedProtocol: String
    ): CompletableFuture<SecureChannel.Session> {
        val handshakeComplete = CompletableFuture<SecureChannel.Session>()
        // Packet length codec should stay forever.
        ch.pushHandler(handshakeFactory.lengthCodec())
        // Handshake and ReadTimeout handlers are to be removed when handshake is complete
        handshakeFactory.create(localKey, handshakeComplete, ch.isInitiator, computationObserver, preparedIdentity)
            .install(ch)

        return handshakeComplete
    } // initChannel
} // class NoiseXXSecureChannel

class NoiseIoHandshake internal constructor(
    private val localKey: PrivKey,
    private val handshakeComplete: CompletableFuture<SecureChannel.Session>,
    private val role: Role,
    private val computationObserver: ((NoiseHandshakeComputation) -> Unit)?,
    private val preparedIdentity: PreparedNoiseIdentity
) : SimpleChannelInboundHandler<ByteBuf>() {
    @JvmOverloads
    constructor(
        localKey: PrivKey,
        handshakeComplete: CompletableFuture<SecureChannel.Session>,
        role: Role,
        computationObserver: ((NoiseHandshakeComputation) -> Unit)? = null
    ) : this(localKey, handshakeComplete, role, computationObserver, prepareNoiseIdentity(localKey))

    private val readTimeout = timeouts.create(armInitialReadWhenAdded = role == Role.RESP)
    private val handshakeState = HandshakeState(NoiseXXSecureChannel.protocolName, role.intVal)
    private var tail: CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    private val terminal = AtomicBoolean()
    private val pendingFrames = mutableListOf<ByteArray>()
    private val expectedHandshakeFrames = if (role == Role.INIT) 1 else 2
    private var receivedHandshakeFrames = 0
    private var initialized = false
    private var offloadCrypto = false
    private var executionObserved = false

    private var loopHandshakes: MutableSet<NoiseIoHandshake>? = null

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        if (ctx.executor() is SingleThreadEventExecutor) {
            loopHandshakes = handshakesOnLoop.get().also { it.add(this) }
        }
    }

    private var sentNoiseKeyPayload = false
    private var instancePayload: ByteArray? = null
    private var activated = false
    private var remotePubKey: PubKey? = null
    private val remotePeerId: PeerId? get() = remotePubKey?.let { PeerId.fromPubKey(it) }
    private var expectedRemotePeerId: PeerId? = null

    init {
        log.debug("Starting handshake")
    } // init

    internal fun install(ch: P2PChannel) {
        ch.pushHandler(HandshakeReadTimeoutNettyHandlerName, readTimeout)
        ch.pushHandler(HandshakeNettyHandlerName, this)
    }

    private fun initializeHandshake() {
        if (initialized) return

        // copyFrom copies both key arrays; no channel can mutate the host's preparation.
        preparedIdentity.copyStaticKeyTo(handshakeState.localKeyPair)
        val ephemeralPrivateKey = ByteArray(32).also { nonBlockingSecureRandom().nextBytes(it) }
        try {
            // noise-java has no public RNG setter. Its "fixed" ephemeral hook is therefore used
            // only to override the entropy source: these bytes are freshly generated for every
            // NoiseIoHandshake instance, preserving normal one-ephemeral-key-per-handshake semantics.
            handshakeState.fixedEphemeralKey.setPrivateKey(ephemeralPrivateKey, 0)
        } finally {
            // noise-java copies the private key bytes into its own DHState.
            ephemeralPrivateKey.fill(0)
        }
        handshakeState.start()
        initialized = true
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (activated) return
        activated = true

        // even though both the alice and bob parties can have the payload ready
        // the Noise protocol only permits alice to send a packet first
        if (role == Role.INIT) {
            if (!ctx.channel().hasAttr(REMOTE_PEER_ID)) {
                throw SecureHandshakeError("Remote Peer ID missing for initiating party")
            }
            expectedRemotePeerId = ctx.channel().attr(REMOTE_PEER_ID).get()
            enqueueCrypto(ctx, null)
        }
    } // channelActive

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        channelActive(ctx)
        if (terminal.get()) return
        readTimeout.disarm()

        val frame = msg.toByteArray()
        receivedHandshakeFrames++
        if (receivedHandshakeFrames <= expectedHandshakeFrames) {
            enqueueCrypto(ctx, frame)
        } else {
            // The final handshake task may still be waiting for a crypto-pool thread while
            // encrypted mux/application frames are already readable. Keep those frames on
            // the event loop until the Noise codec has been installed, then replay them in
            // the same event-loop task that rewires the pipeline.
            pendingFrames += frame
        }
    } // channelRead0

    private fun processHandshakeFrame(ctx: ChannelHandlerContext, frame: ByteArray) {
        if (handshakeState.action != HandshakeState.READ_MESSAGE) {
            throw SecureHandshakeError("Noise handshake received a frame while action was ${handshakeState.action}")
        }
        readNoiseMessage(frame)

        // verify the signature of the remote's noise static public key once
        // the remote public key has been provided by the XX protocol
        val derivedRemotePublicKey = handshakeState.remotePublicKey
        if (derivedRemotePublicKey.hasPublicKey()) {
            remotePubKey = verifyPayload(instancePayload!!, derivedRemotePublicKey)
            if (role == Role.INIT && expectedRemotePeerId != remotePeerId) {
                throw InvalidRemotePubKey()
            }
        }

        // after reading messages and setting up state, write next message onto the wire
        if (handshakeState.action == HandshakeState.WRITE_MESSAGE) {
            sendNoiseStaticKeyAsPayload(ctx)
        }

        if (handshakeState.action == HandshakeState.SPLIT) {
            splitHandshake(ctx)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        handshakeFailed(ctx, cause)
        ctx.channel().close()
    } // exceptionCaught

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        handshakeFailed(ctx, "Connection was closed ${ctx.channel()}")
        super.channelUnregistered(ctx)
    } // channelUnregistered

    private fun readNoiseMessage(msg: ByteArray) {
        log.debug("Noise handshake READ_MESSAGE")

        var payload = ByteArray(msg.size)
        var payloadLength = handshakeState.readMessage(msg, 0, msg.size, payload, 0)

        if (NoiseXXSecureChannel.rustInteroperability) {
            if (payloadLength > 0) {
                if (payloadLength < 2) throw SecureHandshakeError()
                payloadLength = payload.sliceArray(0..1).toUShortBigEndian()
                if (payload.size < payloadLength + 2) throw SecureHandshakeError()
                payload = payload.sliceArray(2 until payloadLength + 2)
            }
        }

        log.trace("msg.size:" + msg.size)
        log.trace("Read message size:$payloadLength")

        if (instancePayload == null && payloadLength > 0) {
            // currently, only allow storing a single payload for verification (this should maybe be changed to a queue)
            instancePayload = ByteArray(payloadLength)
            payload.copyInto(instancePayload!!, 0, 0, payloadLength)
        }
    } // readNoiseMessage

    /**
     * Sends the next Noise message with a payload of the identities and signatures
     * Currently does not include additional data in the payload.
     */
    private fun sendNoiseStaticKeyAsPayload(ctx: ChannelHandlerContext) {
        // only send the Noise static key once
        if (sentNoiseKeyPayload) return
        sentNoiseKeyPayload = true

        // create the message with the signed payload -
        // verification happens once the noise static key is shared
        log.debug("Sending signed Noise static public key as payload")
        sendNoiseMessage(ctx, preparedIdentity.payload.toByteArray())
    } // sendNoiseStaticKeyAsPayload

    private fun sendNoiseMessage(ctx: ChannelHandlerContext, msg: ByteArray? = null) {
        val lenMsg = if (!NoiseXXSecureChannel.rustInteroperability) {
            msg
        } else if (msg != null) {
            msg.size.uShortToBytesBigEndian() + msg
        } else {
            null
        }
        val msgLength = lenMsg?.size ?: 0

        val outputBuffer = ByteArray(msgLength + (2 * (handshakeState.localKeyPair.publicKeyLength + 16))) // 16 is MAC length
        val outputLength = handshakeState.writeMessage(outputBuffer, 0, lenMsg, 0, msgLength)

        log.debug("Noise handshake WRITE_MESSAGE")
        log.trace("Sent message length:$outputLength")

        val expectsRemoteResponse = handshakeState.action == HandshakeState.READ_MESSAGE
        ctx.writeAndFlush(outputBuffer.copyOfRange(0, outputLength).toByteBuf())
            .addListener(writeListeners.create(this, ctx, expectsRemoteResponse))
    }

    private fun afterWrite(ctx: ChannelHandlerContext, expectsRemoteResponse: Boolean, write: ChannelFuture) {
        if (write.isSuccess && expectsRemoteResponse && !terminal.get()) {
            readTimeout.arm()
        } else if (!write.isSuccess && write.cause() != null) {
            // The handshake owns this write. Forwarding from its context skips
            // its exception handler and leaves the connect future pending.
            exceptionCaught(ctx, write.cause())
        }
    }

    private fun verifyPayload(
        payload: ByteArray,
        remotePublicKeyState: DHState
    ): PubKey {
        log.debug("Verifying noise static key payload")

        val (pubKeyFromMessage, signatureFromMessage) = unpackKeyAndSignature(payload)

        val verified = pubKeyFromMessage.verify(
            noiseSignaturePhrase(remotePublicKeyState),
            signatureFromMessage
        )

        log.debug("Remote verification is $verified")

        if (!verified) {
            throw InvalidRemotePubKey()
        }

        return pubKeyFromMessage
    } // verifyPayload

    private fun unpackKeyAndSignature(payload: ByteArray): Pair<PubKey, ByteArray> {
        val noiseMsg = Spipe.NoiseHandshakePayload.parseFrom(payload)

        val publicKey = unmarshalPublicKey(noiseMsg.libp2PKey.toByteArray())
        val signature = noiseMsg.noiseStaticKeySignature.toByteArray()

        return Pair(publicKey, signature)
    } // unpackKeyAndSignature

    private fun splitHandshake(ctx: ChannelHandlerContext) {
        val cipherStatePair = handshakeState.split()

        val aliceSplit = cipherStatePair.sender
        val bobSplit = cipherStatePair.receiver
        log.debug("Split complete")

        // put alice and bob security sessions into the context and trigger the next action
        val secureSession = sessions.create(
            PeerId.fromPubKey(localKey.publicKey()),
            remotePeerId!!,
            remotePubKey!!,
            aliceSplit,
            bobSplit
        )

        if (offloadCrypto) {
            ctx.executor().execute(readyTasks.create(this, ctx, secureSession))
        } else {
            handshakeSucceeded(ctx, secureSession)
        }
    } // splitHandshake

    private fun handshakeSucceeded(ctx: ChannelHandlerContext, session: NoiseSecureChannelSession) {
        if (!claimTerminal(ctx)) return

        try {
            val pipeline = ctx.pipeline()
            val lengthCodecContext = pipeline.context(UShortLengthCodec::class.java)
                ?: throw SecureHandshakeError("Noise handshake length codec missing from ${ctx.channel()}")

            pipeline.addBefore(
                HandshakeNettyHandlerName,
                NoiseCodeNettyHandlerName,
                codecs.create(session.aliceCipher, session.bobCipher)
            )
            // according to Libp2p spec transport payload should also be length-prefixed
            // though Rust Libp2p implementation doesn't do it
            // https://github.com/libp2p/specs/tree/master/noise#wire-format
//        ctx.pipeline()
//            .addBefore(HandshakeNettyHandlerName, "NoiseXXPayloadLenCodec", UShortLengthCodec())
            pipeline.addAfter(
                NoiseCodeNettyHandlerName,
                "NoisePacketSplitter",
                SplitEncoder(MaxCipheredPacketLength - session.aliceCipher.macLength)
            )
            removeHandshakeHandlers(ctx)

            // Completing the future synchronously installs the mux negotiator through
            // ConnectionUpgrader.thenCompose. It must exist before pending encrypted
            // mux frames are replayed or those frames reach the pipeline tail and are lost.
            handshakeComplete.complete(session)
            pendingFrames.forEach {
                lengthCodecContext.fireChannelRead(it.toByteBuf())
            }
            pendingFrames.clear()

            lengthCodecContext.fireChannelActive()
        } catch (cause: Throwable) {
            failAfterTerminalClaim(ctx, cause)
            ctx.channel().close()
        } finally {
            releaseLoopHandshake()
        }
    } // handshakeSucceeded

    private fun handshakeFailed(ctx: ChannelHandlerContext, cause: String) {
        handshakeFailed(ctx, Exception(cause))
    }
    private fun handshakeFailed(ctx: ChannelHandlerContext, cause: Throwable) {
        if (!claimTerminal(ctx)) return
        try {
            failAfterTerminalClaim(ctx, cause)
        } finally {
            releaseLoopHandshake()
        }
    }

    private fun releaseLoopHandshake() {
        loopHandshakes?.remove(this)
        loopHandshakes = null
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        // Normal terminal cleanup retains membership through the ready callbacks.
        // An external removal before completion must not retain a loop entry.
        if (!terminal.get()) releaseLoopHandshake()
    }

    private fun claimTerminal(ctx: ChannelHandlerContext): Boolean {
        if (!terminal.compareAndSet(false, true)) return false
        check(ctx.executor().inEventLoop()) { "Noise terminal transition must run on its event loop: ${ctx.channel()}" }
        return true
    }

    private fun failAfterTerminalClaim(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("Noise handshake failed", cause)
        executionHooks.failureObserver?.invoke(cause)

        handshakeComplete.completeExceptionally(cause)
        removeHandshakeHandlers(ctx)
    }

    private fun removeHandshakeHandlers(ctx: ChannelHandlerContext) {
        val pipeline = ctx.pipeline()
        if (pipeline.context(HandshakeReadTimeoutNettyHandlerName) != null) {
            pipeline.remove(HandshakeReadTimeoutNettyHandlerName)
        }
        if (pipeline.context(this) != null) {
            pipeline.remove(this)
        }
    }

    // A null frame is the initiator's first write; received frames (even empty ones) are non-null.
    private fun runCrypto(ctx: ChannelHandlerContext, pendingOnLoop: Int, frame: ByteArray?, offloaded: Boolean) {
        if (offloaded && terminal.get()) return
        computationObserver?.invoke(computations.create(ctx.channel().id().asLongText(), ctx.executor().inEventLoop(), pendingOnLoop))
        if (terminal.get() || !ctx.channel().isActive) return
        executionHooks.cryptoTaskObserver?.invoke(offloaded, ctx.executor().inEventLoop())
        initializeHandshake()
        if (frame == null) sendNoiseMessage(ctx) else processHandshakeFrame(ctx, frame)
    }

    private fun enqueueCrypto(ctx: ChannelHandlerContext, frame: ByteArray?) {
        if (terminal.get()) return

        val pendingOnLoop = loopHandshakes?.size ?: 1
        // This loop owns both its pending handshakes and the tasks waiting for it.
        // Once queued, keep this channel on the same FIFO for the rest of its handshake.
        val loopExecutor = ctx.executor() as? SingleThreadEventExecutor
        offloadCrypto = offloadCrypto ||
            (loopExecutor != null && (pendingOnLoop > 1 || loopExecutor.pendingTasks() > 0))
        if (!executionObserved) {
            executionObserved = true
            executionHooks.handshakeObserver?.invoke(offloadCrypto)
        }
        if (!offloadCrypto) {
            runCrypto(ctx, pendingOnLoop, frame, false)
            return
        }

        val taskExecutor =
            executionHooks.cryptoExecutorSelector?.invoke(role, receivedHandshakeFrames)
                ?: cryptoExecutor
        val next = tail.thenRunAsync(cryptoTasks.create(this, ctx, pendingOnLoop, frame), taskExecutor)
        tail = next
        next.whenComplete(completions.create(this, ctx))
    }

    private fun afterComputation(ctx: ChannelHandlerContext, failure: Throwable?) {
        if (failure != null) {
            val cause = if (failure is CompletionException && failure.cause != null) failure.cause!! else failure
            ctx.executor().execute(failureTasks.create(this, ctx, cause))
        }
    }

    private class CryptoTask(
        val owner: NoiseIoHandshake,
        val ctx: ChannelHandlerContext,
        val pending: Int,
        val frame: ByteArray?
    ) : Runnable {
        override fun run() = owner.runCrypto(ctx, pending, frame, true)
        companion object Factory {
            fun create(owner: NoiseIoHandshake, ctx: ChannelHandlerContext, pending: Int, frame: ByteArray?) =
                CryptoTask(owner, ctx, pending, frame)
        }
    }

    private class Completion(val owner: NoiseIoHandshake, val ctx: ChannelHandlerContext) : BiConsumer<Void?, Throwable?> {
        override fun accept(unused: Void?, failure: Throwable?) = owner.afterComputation(ctx, failure)
        companion object Factory {
            fun create(owner: NoiseIoHandshake, ctx: ChannelHandlerContext) = Completion(owner, ctx)
        }
    }

    private class FailureTask(val owner: NoiseIoHandshake, val ctx: ChannelHandlerContext, val cause: Throwable) : Runnable {
        override fun run() {
            if (!owner.terminal.get()) {
                owner.handshakeFailed(ctx, cause)
                ctx.channel().close()
            }
        }
        companion object Factory {
            fun create(owner: NoiseIoHandshake, ctx: ChannelHandlerContext, cause: Throwable) = FailureTask(owner, ctx, cause)
        }
    }

    private class WriteListener(
        val owner: NoiseIoHandshake,
        val ctx: ChannelHandlerContext,
        val expectsRemoteResponse: Boolean
    ) : ChannelFutureListener {
        override fun operationComplete(write: ChannelFuture) = owner.afterWrite(ctx, expectsRemoteResponse, write)
        companion object Factory {
            fun create(owner: NoiseIoHandshake, ctx: ChannelHandlerContext, expectsRemoteResponse: Boolean) =
                WriteListener(owner, ctx, expectsRemoteResponse)
        }
    }

    private class ReadyTask(val owner: NoiseIoHandshake, val ctx: ChannelHandlerContext, val session: NoiseSecureChannelSession) : Runnable {
        override fun run() = owner.handshakeSucceeded(ctx, session)
        companion object Factory {
            fun create(owner: NoiseIoHandshake, ctx: ChannelHandlerContext, session: NoiseSecureChannelSession) = ReadyTask(owner, ctx, session)
        }
    }

    internal companion object Factory {
        private val timeouts = NoiseHandshakeReadTimeoutHandler
        private val lengthCodecs = UShortLengthCodec
        private val sessions = NoiseSecureChannelSession
        private val codecs = NoiseXXCodec
        private val computations = NoiseHandshakeComputation
        private val cryptoTasks = CryptoTask
        private val completions = Completion
        private val failureTasks = FailureTask
        private val writeListeners = WriteListener
        private val readyTasks = ReadyTask
        private val executionHooks = NoiseHandshakeExecutionTestHook
        private val initiator = Role.INIT
        private val responder = Role.RESP

        fun lengthCodec() = lengthCodecs.create()

        fun create(
            localKey: PrivKey,
            handshakeComplete: CompletableFuture<SecureChannel.Session>,
            isInitiator: Boolean,
            computationObserver: ((NoiseHandshakeComputation) -> Unit)?,
            preparedIdentity: PreparedNoiseIdentity
        ) = NoiseIoHandshake(localKey, handshakeComplete, if (isInitiator) initiator else responder, computationObserver, preparedIdentity)

        private val handshakesOnLoop = object : FastThreadLocal<MutableSet<NoiseIoHandshake>>() {
            override fun initialValue(): MutableSet<NoiseIoHandshake> = mutableSetOf()
        }
        private val cryptoThreadNumber = AtomicInteger()
        private val cryptoExecutor: ExecutorService = run {
            val threadCount = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)
            Executors.newFixedThreadPool(threadCount) { runnable ->
                Thread(runnable, "noise-handshake-crypto-${cryptoThreadNumber.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
    }
} // class NoiseIoHandshake

@VisibleForTesting
internal object NoiseHandshakeExecutionTestHook {
    @Volatile
    var handshakeObserver: ((offloaded: Boolean) -> Unit)? = null

    @Volatile
    var cryptoTaskObserver: ((offloaded: Boolean, onEventLoop: Boolean) -> Unit)? = null

    @Volatile
    var failureObserver: ((cause: Throwable) -> Unit)? = null

    @Volatile
    var cryptoExecutorSelector: ((role: Role, receivedHandshakeFrames: Int) -> Executor?)? = null
}

private fun noiseSignaturePhrase(dhState: DHState) =
    "noise-libp2p-static-key:".toByteArray() + dhState.publicKey

private val DHState.publicKey: ByteArray
    get() {
        val pubKey = ByteArray(publicKeyLength)
        getPublicKey(pubKey, 0)
        return pubKey
    }

internal class PreparedNoiseIdentity(private val staticKey: DHState, val payload: ByteString) {
    fun copyStaticKeyTo(target: DHState) {
        target.copyFrom(staticKey)
    }
}

private fun prepareNoiseIdentity(localKey: PrivKey): PreparedNoiseIdentity {
    val staticKey = Noise.createDH("25519")
    val privateBytes = NoiseXXSecureChannel.localStaticPrivateKey25519.copyOf()
    try {
        staticKey.setPrivateKey(privateBytes, 0)
        val publicKey = marshalPublicKey(localKey.publicKey())
        val signature = localKey.sign(noiseSignaturePhrase(staticKey))
        val payload = Spipe.NoiseHandshakePayload.newBuilder()
            .setLibp2PKey(ByteString.copyFrom(publicKey))
            .setNoiseStaticKeySignature(ByteString.copyFrom(signature))
            .setLibp2PData(ByteString.EMPTY)
            .setLibp2PDataSignature(ByteString.EMPTY)
            .build()
            .toByteString()
        return PreparedNoiseIdentity(staticKey, payload)
    } catch (failure: Throwable) {
        try {
            staticKey.destroy()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    } finally {
        privateBytes.fill(0)
    }
}
