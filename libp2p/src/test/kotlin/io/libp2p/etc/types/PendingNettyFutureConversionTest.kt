package io.libp2p.etc.types

import io.netty.channel.DefaultChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.DefaultEventExecutor
import io.netty.util.concurrent.DefaultPromise
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class PendingNettyFutureConversionTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun pendingSourceLaterFailureKeepsOriginalCause(channelFuture: Boolean) {
        val executor = DefaultEventExecutor()
        val channel = EmbeddedChannel()
        try {
            val failure = IOException("The pending operation failed after conversion returned")
            val source = if (channelFuture) DefaultChannelPromise(channel, executor) else DefaultPromise<String>(executor)
            val converted: CompletableFuture<*> = if (source is DefaultChannelPromise) {
                source.toCompletableFuture()
            } else {
                source.toCompletableFuture()
            }
            assertFalse(source.isDone)
            assertFalse(converted.isDone)
            executor.submit { source.setFailure(failure) }.get(5, TimeUnit.SECONDS)
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(converted.isDone, "A later failure must reach the converted future after listener dispatch")
            assertTrue(converted.isCompletedExceptionally)
            assertFalse(converted.isCancelled)
            val reported = assertThrows(ExecutionException::class.java) { converted.get(5, TimeUnit.SECONDS) }
            assertSame(failure, reported.cause)
            assertEquals("The pending operation failed after conversion returned", reported.cause!!.message)
            assertSame(failure, source.cause())
        } finally {
            channel.finishAndReleaseAll()
            executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun pendingSourceLaterCancellationKeepsExactTerminalState(channelFuture: Boolean) {
        val executor = DefaultEventExecutor()
        val channel = EmbeddedChannel()
        try {
            val source = if (channelFuture) DefaultChannelPromise(channel, executor) else DefaultPromise<String>(executor)
            val converted: CompletableFuture<*> = if (source is DefaultChannelPromise) {
                source.toCompletableFuture()
            } else {
                source.toCompletableFuture()
            }
            assertFalse(source.isDone)
            assertFalse(converted.isDone)
            executor.submit { assertTrue(source.cancel(false)) }.get(5, TimeUnit.SECONDS)
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(converted.isDone, "A later cancellation must reach the converted future after listener dispatch")
            assertTrue(converted.isCompletedExceptionally)
            assertTrue(converted.isCancelled)
            assertSame(source.cause(), assertThrows(CancellationException::class.java) { converted.join() })
            assertTrue(source.isCancelled)
        } finally {
            channel.finishAndReleaseAll()
            executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS)
        }
    }
}
