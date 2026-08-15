package io.libp2p.pubsub

import io.netty.util.ResourceLeakDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener

class PubsubRouterLeakDetectionLifecycleTest {

    @Test
    fun `pubsub router tests restore the process leak detection level`() {
        val originalLevel = ResourceLeakDetector.getLevel()
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED)
        try {
            val listener = SummaryGeneratingListener()
            LauncherFactory.create().execute(
                request()
                    .selectors(selectMethod(LeakDetectionFixture::class.java, "fixtureMethod"))
                    .build(),
                listener
            )

            assertThat(listener.summary.testsSucceededCount).isEqualTo(1)
            assertThat(listener.summary.testsFailedCount).isZero()
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.ADVANCED)
        } finally {
            ResourceLeakDetector.setLevel(originalLevel)
        }
    }

    class LeakDetectionFixture : PubsubRouterTest(DeterministicFuzz.createFloodFuzzRouterFactory()) {
        @Test
        fun fixtureMethod() {
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
        }
    }
}
