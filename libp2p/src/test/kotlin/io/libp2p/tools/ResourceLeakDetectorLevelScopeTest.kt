package io.libp2p.tools

import io.netty.util.ResourceLeakDetector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ResourceLeakDetectorLevelScopeTest {

    @Test
    fun `restore before enable reports that the scope was never enabled`() {
        val scope = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)

        assertThatThrownBy { scope.restore() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("The Netty leak-detection level cannot be restored before it has been scoped for a test")
    }

    @Test
    fun `double enable is rejected without changing the active ownership`() {
        val originalLevel = ResourceLeakDetector.getLevel()
        val scope = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)
        scope.enable()
        try {
            assertThatThrownBy { scope.enable() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("The Netty leak-detection level is already scoped for this test")
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
        } finally {
            scope.restore()
        }

        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(originalLevel)
    }

    @Test
    fun `double restore reports that the completed scope was already restored`() {
        val originalLevel = ResourceLeakDetector.getLevel()
        val scope = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)
        scope.enable()
        scope.restore()

        assertThatThrownBy { scope.restore() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("The Netty leak-detection level has already been restored for this test")
        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(originalLevel)
    }

    @Test
    fun `failed conflicting acquisition stays inactive and cannot release the active owner`() {
        val originalLevel = ResourceLeakDetector.getLevel()
        val owner = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)
        val conflicting = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.SIMPLE)
        owner.enable()
        try {
            assertThatThrownBy { conflicting.enable() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage(
                    "The Netty leak-detection level is already scoped to PARANOID, so it cannot also be scoped to " +
                        "SIMPLE"
                )
            assertThatThrownBy { conflicting.restore() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage(
                    "The Netty leak-detection level cannot be restored before it has been scoped for a test"
                )
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
        } finally {
            owner.restore()
        }

        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(originalLevel)
    }

    @Test
    fun `completed scope can be reused without retaining ownership`() {
        val originalLevel = ResourceLeakDetector.getLevel()
        val scope = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)
        scope.enable()
        try {
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
        } finally {
            scope.restore()
        }
        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(originalLevel)

        scope.enable()
        try {
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
        } finally {
            scope.restore()
        }

        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(originalLevel)
    }

    @Test
    fun `concurrent enable on one scope acquires exactly one ownership count`() {
        val originalLevel = ResourceLeakDetector.getLevel()
        val guardian = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)
        val shared = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)
        val callersReady = CountDownLatch(2)
        val allowEnable = CountDownLatch(1)
        val callersFinished = CountDownLatch(2)
        val successfulEnables = AtomicInteger()
        val enableFailures = ConcurrentLinkedQueue<Throwable>()
        var guardianEnabled = false
        var sharedRestored = false

        val callers = (1..2).map { caller ->
            thread(name = "leak-detection-scope-enable-$caller") {
                try {
                    callersReady.countDown()
                    check(allowEnable.await(10, TimeUnit.SECONDS)) {
                        "Concurrent scope callers were not released within 10 seconds"
                    }
                    shared.enable()
                    successfulEnables.incrementAndGet()
                } catch (throwable: Throwable) {
                    enableFailures.add(throwable)
                } finally {
                    callersFinished.countDown()
                }
            }
        }

        try {
            guardian.enable()
            guardianEnabled = true
            assertThat(callersReady.await(10, TimeUnit.SECONDS))
                .describedAs("Both concurrent scope callers should be ready")
                .isTrue()
            allowEnable.countDown()
            assertThat(callersFinished.await(10, TimeUnit.SECONDS))
                .describedAs("Both concurrent scope callers should finish")
                .isTrue()

            assertThat(successfulEnables.get()).isEqualTo(1)
            assertThat(enableFailures).hasSize(1)
            assertThat(enableFailures.single())
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("The Netty leak-detection level is already scoped for this test")
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)

            shared.restore()
            sharedRestored = true
            assertThat(ResourceLeakDetector.getLevel())
                .describedAs("The guardian scope should retain its ownership after the shared scope restores")
                .isEqualTo(ResourceLeakDetector.Level.PARANOID)
            assertThatThrownBy { shared.restore() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("The Netty leak-detection level has already been restored for this test")
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
        } finally {
            allowEnable.countDown()
            callers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
            if (successfulEnables.get() == 1 && !sharedRestored) {
                shared.restore()
            }
            if (guardianEnabled) {
                guardian.restore()
            }
        }

        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(originalLevel)
    }
}
