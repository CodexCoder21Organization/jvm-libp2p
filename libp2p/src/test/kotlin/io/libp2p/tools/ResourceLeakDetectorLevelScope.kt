package io.libp2p.tools

import io.netty.util.ResourceLeakDetector

class ResourceLeakDetectorLevelScope(
    private val testLevel: ResourceLeakDetector.Level
) {
    private var state = ResourceLeakDetectorLevelScopeState.NEVER_ENABLED

    @Synchronized
    fun enable() {
        check(state != ResourceLeakDetectorLevelScopeState.ENABLED) {
            "The Netty leak-detection level is already scoped for this test"
        }
        ResourceLeakDetectorLevelCoordinator.acquire(testLevel)
        state = ResourceLeakDetectorLevelScopeState.ENABLED
    }

    @Synchronized
    fun restore() {
        when (state) {
            ResourceLeakDetectorLevelScopeState.NEVER_ENABLED -> error(
                "The Netty leak-detection level cannot be restored before it has been scoped for a test"
            )
            ResourceLeakDetectorLevelScopeState.RESTORED -> error(
                "The Netty leak-detection level has already been restored for this test"
            )
            ResourceLeakDetectorLevelScopeState.ENABLED -> Unit
        }
        ResourceLeakDetectorLevelCoordinator.release(testLevel)
        state = ResourceLeakDetectorLevelScopeState.RESTORED
    }
}

private enum class ResourceLeakDetectorLevelScopeState {
    NEVER_ENABLED,
    ENABLED,
    RESTORED
}

private object ResourceLeakDetectorLevelCoordinator {
    private var originalLevel: ResourceLeakDetector.Level? = null
    private var scopedLevel: ResourceLeakDetector.Level? = null
    private var ownerCount = 0

    @Synchronized
    fun acquire(requestedLevel: ResourceLeakDetector.Level) {
        if (ownerCount == 0) {
            check(originalLevel == null && scopedLevel == null) {
                "The Netty leak-detection level coordinator retained state without an active test scope"
            }
            val currentLevel = ResourceLeakDetector.getLevel()
            ResourceLeakDetector.setLevel(requestedLevel)
            originalLevel = currentLevel
            scopedLevel = requestedLevel
            ownerCount = 1
            return
        }

        check(scopedLevel == requestedLevel) {
            "The Netty leak-detection level is already scoped to $scopedLevel, so it cannot also be scoped to " +
                requestedLevel
        }
        ownerCount++
    }

    @Synchronized
    fun release(requestedLevel: ResourceLeakDetector.Level) {
        val activeLevel = checkNotNull(scopedLevel) {
            "The Netty leak-detection level coordinator has no active test scope to restore"
        }
        check(activeLevel == requestedLevel) {
            "The Netty leak-detection level coordinator is scoped to $activeLevel, not $requestedLevel"
        }
        check(ownerCount > 0) {
            "The Netty leak-detection level coordinator has no active owner for $activeLevel"
        }

        if (ownerCount > 1) {
            ownerCount--
            return
        }

        val levelToRestore = checkNotNull(originalLevel) {
            "The Netty leak-detection level coordinator did not retain the original level"
        }
        ResourceLeakDetector.setLevel(levelToRestore)
        ownerCount = 0
        originalLevel = null
        scopedLevel = null
    }
}
