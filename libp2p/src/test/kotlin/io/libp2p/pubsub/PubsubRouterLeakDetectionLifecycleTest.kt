package io.libp2p.pubsub

import io.libp2p.tools.ResourceLeakDetectorLevelScope
import io.netty.util.ResourceLeakDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PubsubRouterLeakDetectionLifecycleTest {

    @Test
    fun `pubsub router tests restore the process leak detection level`() {
        val originalLevel = ResourceLeakDetector.getLevel()
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED)
        try {
            val scope = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)
            scope.enable()

            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)

            scope.restore()
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.ADVANCED)
        } finally {
            ResourceLeakDetector.setLevel(originalLevel)
        }
    }
}
