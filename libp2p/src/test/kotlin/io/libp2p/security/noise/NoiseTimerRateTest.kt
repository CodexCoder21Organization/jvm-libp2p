package io.libp2p.security.noise

import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class NoiseTimerRateTest {
    @TestFactory
    fun testRemoteWaitAttempts() = (1..200).map { attempt ->
        dynamicTest("testRemoteWait$attempt") {
            NoiseHandshakeOffEventLoopTest().`initiator times out silent responder while waiting for message 2`()
        }
    }
}
