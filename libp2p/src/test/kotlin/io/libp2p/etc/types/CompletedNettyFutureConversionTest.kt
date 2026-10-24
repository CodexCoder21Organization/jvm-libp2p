package io.libp2p.etc.types

import io.netty.channel.DefaultChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.DefaultEventExecutor
import io.netty.util.concurrent.DefaultPromise
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadFactory

class CompletedNettyFutureConversionTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun completionDuringListenerRegistrationRemainsObservable(channelFuture: Boolean) {
        withUnavailableListenerExecutor(true) { executor ->
            val channel = EmbeddedChannel()
            try {
                if (channelFuture) {
                    val source = object : DefaultChannelPromise(channel, executor) {
                        override fun addListener(listener: GenericFutureListener<out Future<in Void>>): io.netty.channel.ChannelPromise {
                            trySuccess()
                            return super.addListener(listener)
                        }
                    }
                    val converted = source.toCompletableFuture()
                    assertTrue(converted.isDone, "Completion during channel-listener registration must remain observable")
                    assertSame(channel, converted.join())
                } else {
                    val source = object : DefaultPromise<String>(executor) {
                        override fun addListener(listener: GenericFutureListener<out Future<in String>>): io.netty.util.concurrent.Promise<String> {
                            trySuccess("completed-during-registration")
                            return super.addListener(listener)
                        }
                    }
                    val converted = source.toCompletableFuture()
                    assertTrue(converted.isDone, "Completion during listener registration must remain observable")
                    assertEquals("completed-during-registration", converted.join())
                }
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun completedChannelResultsDoNotNeedTheirListenerExecutor(terminated: Boolean) {
        withUnavailableListenerExecutor(terminated) { executor ->
            val channel = EmbeddedChannel()
            try {
                val failure = IOException("The test channel close failed before future conversion")
                val success = DefaultChannelPromise(channel, executor).setSuccess()
                val failed = DefaultChannelPromise(channel, executor).setFailure(failure)
                val cancelled = DefaultChannelPromise(channel, executor).also { it.cancel(false) }
                val converted = success.toCompletableFuture()
                assertTrue(converted.isDone, "A settled channel result must remain observable when listener submission is rejected")
                assertSame(channel, converted.get(5, TimeUnit.SECONDS))
                val reported = assertThrows(ExecutionException::class.java) {
                    failed.toCompletableFuture().get(5, TimeUnit.SECONDS)
                }
                assertSame(failure, reported.cause)
                assertTrue(cancelled.toCompletableFuture().isCancelled)
                assertThrows(CancellationException::class.java) { cancelled.toCompletableFuture().join() }
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun completedGenericResultsDoNotNeedTheirListenerExecutor(terminated: Boolean) {
        withUnavailableListenerExecutor(terminated) { executor ->
            val failure = IOException("The test operation failed before future conversion")
            val success = DefaultPromise<String>(executor).setSuccess("completed")
            val failed = DefaultPromise<String>(executor).setFailure(failure)
            val cancelled = DefaultPromise<String>(executor).also { it.cancel(false) }
            val converted = success.toCompletableFuture()
            assertTrue(converted.isDone, "A settled operation must remain observable when listener submission is rejected")
            assertEquals("completed", converted.get(5, TimeUnit.SECONDS))
            val reported = assertThrows(ExecutionException::class.java) {
                failed.toCompletableFuture().get(5, TimeUnit.SECONDS)
            }
            assertSame(failure, reported.cause)
            assertTrue(cancelled.toCompletableFuture().isCancelled)
        }
    }
}

private fun withUnavailableListenerExecutor(terminated: Boolean, action: (DefaultEventExecutor) -> Unit) {
    val executor = DefaultEventExecutor(
        null,
        ThreadFactory { task -> Thread(task, "completed-future-listener").apply { isDaemon = true } },
        16,
        io.netty.util.concurrent.RejectedExecutionHandlers.reject()
    )
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    try {
        if (terminated) {
            executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
        } else {
            executor.execute {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "The test must release its listener executor" }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            var rejected = false
            for (index in 0..16) {
                try {
                    executor.execute { }
                } catch (_: RejectedExecutionException) {
                    rejected = true
                    break
                }
            }
            check(rejected) { "The real bounded listener queue must be full" }
            check(!executor.isShuttingDown) { "Queue rejection must occur on a live executor" }
        }
        action(executor)
    } finally {
        release.countDown()
        executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
    }
}
