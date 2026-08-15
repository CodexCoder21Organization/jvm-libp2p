package io.libp2p.tools

import io.netty.util.ResourceLeakDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener

class ResourceLeakDetectorLevelScopeAmbientOwnerTest {

    @Test
    fun `helper contracts pass beside an already active paranoid owner`() {
        val originalLevel = ResourceLeakDetector.getLevel()
        val outerScope = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)
        var outerScopeEnabled = false
        var primaryFailure: Throwable? = null

        try {
            outerScope.enable()
            outerScopeEnabled = true
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)

            val listener = SummaryGeneratingListener()
            LauncherFactory.create().execute(
                request()
                    .selectors(selectClass(ResourceLeakDetectorLevelScopeTest::class.java))
                    .build(),
                listener
            )

            assertThat(listener.summary.testsFoundCount).isEqualTo(6)
            assertThat(listener.summary.testsSucceededCount).isEqualTo(6)
            assertThat(listener.summary.testsFailedCount).isZero()
            assertThat(listener.summary.testsAbortedCount).isZero()
            assertThat(listener.summary.testsSkippedCount).isZero()
            assertThat(ResourceLeakDetector.getLevel())
                .describedAs("The outer owner must remain active after all nested helper contracts finish")
                .isEqualTo(ResourceLeakDetector.Level.PARANOID)
        } catch (throwable: Throwable) {
            primaryFailure = throwable
        } finally {
            if (outerScopeEnabled) {
                primaryFailure = captureCleanupFailure(primaryFailure) {
                    outerScope.restore()
                }
            }
            primaryFailure = captureCleanupFailure(primaryFailure) {
                assertThat(ResourceLeakDetector.getLevel())
                    .describedAs("The outer owner must restore the level captured before the test")
                    .isEqualTo(originalLevel)
            }
        }

        primaryFailure?.let { throw it }
    }
}

private fun captureCleanupFailure(primaryFailure: Throwable?, cleanup: () -> Unit): Throwable? {
    return try {
        cleanup()
        primaryFailure
    } catch (cleanupFailure: Throwable) {
        if (primaryFailure == null) {
            cleanupFailure
        } else {
            primaryFailure.addSuppressed(cleanupFailure)
            primaryFailure
        }
    }
}
