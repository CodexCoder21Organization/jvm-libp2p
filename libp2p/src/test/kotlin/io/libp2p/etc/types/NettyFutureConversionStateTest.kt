package io.libp2p.etc.types

import io.netty.channel.DefaultChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.DefaultEventExecutor
import io.netty.util.concurrent.DefaultPromise
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class NettyFutureConversionStateTest {
    @ParameterizedTest
    @CsvSource("false,failure", "true,failure", "false,cancel", "true,cancel")
    fun terminalFailureDuringRegistrationSurvivesRejectedDispatch(channelFuture: Boolean, outcome: String) {
        val executor = DefaultEventExecutor()
        val channel = EmbeddedChannel()
        val failure = IOException("The operation failed during listener registration")
        try {
            executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS)
            if (channelFuture) {
                val source = object : DefaultChannelPromise(channel, executor) {
                    override fun addListener(listener: GenericFutureListener<out Future<in Void>>): io.netty.channel.ChannelPromise {
                        val attached = super.addListener(listener)
                        if (outcome == "failure") setFailure(failure) else cancel(false)
                        return attached
                    }
                }
                val converted = source.toCompletableFuture()
                assertConversionOutcome(converted, source, outcome, failure, channel)
            } else {
                val source = object : DefaultPromise<String>(executor) {
                    override fun addListener(listener: GenericFutureListener<out Future<in String>>): io.netty.util.concurrent.Promise<String> {
                        val attached = super.addListener(listener)
                        if (outcome == "failure") setFailure(failure) else cancel(false)
                        return attached
                    }
                }
                val converted = source.toCompletableFuture()
                assertConversionOutcome(converted, source, outcome, failure, null)
            }
        } finally {
            channel.finishAndReleaseAll()
            executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS)
        }
    }

    @ParameterizedTest
    @CsvSource("false,success", "true,success", "false,failure", "true,failure", "false,cancel", "true,cancel")
    fun queuedCompletionDrainsWhenExecutorShutsDownAfterAttach(channelFuture: Boolean, outcome: String) {
        val executor = DefaultEventExecutor()
        val channel = EmbeddedChannel()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IOException("The queued operation failed before listener dispatch")
        try {
            executor.execute {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "The test must release its listener executor" }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (channelFuture) {
                val source = DefaultChannelPromise(channel, executor)
                val converted = source.toCompletableFuture()
                assertFalse(converted.isDone)
                when (outcome) {
                    "success" -> source.setSuccess()
                    "failure" -> source.setFailure(failure)
                    else -> assertTrue(source.cancel(false))
                }
                assertFalse(converted.isDone, "A pending source uses its listener executor")
                val termination = executor.shutdownGracefully(0, 5, TimeUnit.SECONDS)
                release.countDown()
                termination.get(5, TimeUnit.SECONDS)
                assertConversionOutcome(converted, source, outcome, failure, channel)
            } else {
                val source = DefaultPromise<String?>(executor)
                val converted = source.toCompletableFuture()
                assertFalse(converted.isDone)
                when (outcome) {
                    "success" -> source.setSuccess(null)
                    "failure" -> source.setFailure(failure)
                    else -> assertTrue(source.cancel(false))
                }
                assertFalse(converted.isDone, "A pending source uses its listener executor")
                val termination = executor.shutdownGracefully(0, 5, TimeUnit.SECONDS)
                release.countDown()
                termination.get(5, TimeUnit.SECONDS)
                assertConversionOutcome(converted, source, outcome, failure, null)
            }
        } finally {
            release.countDown()
            channel.finishAndReleaseAll()
            executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS)
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "true,false", "false,true", "true,true")
    fun callerCompletionDoesNotChangePendingSource(channelFuture: Boolean, cancel: Boolean) {
        val executor = DefaultEventExecutor()
        val channel = EmbeddedChannel()
        try {
            val source = if (channelFuture) DefaultChannelPromise(channel, executor) else DefaultPromise<String>(executor)
            val converted: CompletableFuture<*> = if (source is DefaultChannelPromise) {
                source.toCompletableFuture()
            } else {
                source.toCompletableFuture()
            }
            if (cancel) assertTrue(converted.cancel(false)) else assertTrue(converted.complete(null))
            assertFalse(source.isDone, "Changing the caller's result must not complete the source")
            val failure = IOException("The source still owns its result after caller completion")
            executor.submit { source.setFailure(failure) }.get(5, TimeUnit.SECONDS)
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertSame(failure, source.cause())
            assertEquals(cancel, converted.isCancelled)
            if (!cancel) assertNull(converted.join())
        } finally {
            channel.finishAndReleaseAll()
            executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS)
        }
    }
}

private fun assertConversionOutcome(
    converted: CompletableFuture<*>, source: Future<*>, outcome: String, failure: IOException, success: Any?
) {
    assertTrue(converted.isDone, "The source's terminal result must remain observable")
    assertEquals(outcome != "success", converted.isCompletedExceptionally)
    assertEquals(outcome == "cancel", converted.isCancelled)
    when (outcome) {
        "success" -> assertSame(success, converted.join())
        "failure" -> {
            val reported = assertThrows(ExecutionException::class.java) { converted.get(5, TimeUnit.SECONDS) }
            assertSame(failure, reported.cause)
            assertEquals(failure.message, reported.cause!!.message)
        }
        else -> assertSame(source.cause(), assertThrows(CancellationException::class.java) { converted.join() })
    }
}
