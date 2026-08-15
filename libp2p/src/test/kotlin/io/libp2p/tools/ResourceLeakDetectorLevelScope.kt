package io.libp2p.tools

import io.netty.util.ResourceLeakDetector

class ResourceLeakDetectorLevelScope(
    private val testLevel: ResourceLeakDetector.Level
) {
    private var previousLevel: ResourceLeakDetector.Level? = null

    fun enable() {
        check(previousLevel == null) { "The Netty leak-detection level is already scoped for this test" }
        previousLevel = ResourceLeakDetector.getLevel()
        ResourceLeakDetector.setLevel(testLevel)
    }

    fun restore() {
        val levelToRestore = checkNotNull(previousLevel) {
            "The Netty leak-detection level cannot be restored before it has been scoped for a test"
        }
        ResourceLeakDetector.setLevel(levelToRestore)
        previousLevel = null
    }
}
