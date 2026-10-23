package io.libp2p.security.noise

import com.southernstorm.noise.protocol.Destroyable
import io.netty.util.internal.ThreadExecutorMap
import java.security.MessageDigest
import java.security.Provider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real SHA-256 provider; the child JVM owns its counters and optional first-use action. */
internal class NoisePreparationProvider(
    private val firstUse: () -> Unit = {}
) : Provider("NoisePreparationTestSHA256", "1.0", "SUN SHA-256 with first-use observations") {
    val allocations = AtomicInteger()
    val destroyed = AtomicInteger()
    val duplicateDestructions = AtomicInteger()
    val active = AtomicInteger()
    val maxActive = AtomicInteger()
    val initializations = AtomicInteger()
    val onNettyExecutor = AtomicBoolean()
    private val initialized = AtomicBoolean()

    init {
        putService(object : Service(this, "MessageDigest", "SHA-256", PreparationDigest::class.java.name, null, null) {
            override fun newInstance(constructorParameter: Any?): Any {
                allocations.incrementAndGet()
                val current = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, current) }
                try {
                    if (ThreadExecutorMap.currentExecutor()?.inEventLoop() == true) onNettyExecutor.set(true)
                    if (initialized.compareAndSet(false, true)) {
                        initializations.incrementAndGet()
                        firstUse()
                    }
                    val calls = AtomicInteger()
                    return PreparationDigest {
                        if (calls.incrementAndGet() != 1) duplicateDestructions.incrementAndGet()
                        destroyed.incrementAndGet()
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
        })
    }
}

private class PreparationDigest(private val onDestroy: () -> Unit) : MessageDigest("SHA-256"), Destroyable {
    private val delegate = MessageDigest.getInstance("SHA-256", "SUN")
    override fun engineGetDigestLength(): Int = delegate.digestLength
    override fun engineUpdate(input: Byte) = delegate.update(input)
    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) = delegate.update(input, offset, len)
    override fun engineDigest(): ByteArray = delegate.digest()
    override fun engineReset() = delegate.reset()
    override fun destroy() {
        delegate.reset()
        onDestroy()
    }
}
