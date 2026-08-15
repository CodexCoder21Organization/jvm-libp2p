package io.libp2p.pubsub

import io.libp2p.security.SecureChannelTestBase
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.netty.util.ResourceLeakDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PubsubRouterLeakDetectionLifecycleTest {

    @Test
    fun `overlapping real test lifecycles retain leak detection until the last exit`() {
        val originalLevel = ResourceLeakDetector.getLevel()
        val scenario = LeakDetectionLifecycleScenario()
        LeakDetectionLifecycleFixtureState.install(scenario)
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED)
        var pubsubRun: FixtureRun? = null
        var secureChannelRun: FixtureRun? = null
        try {
            pubsubRun = startFixture(
                PubsubRouterLeakDetectionFixture::class.java,
                "overlappingBody",
                "pubsub-leak-detection-fixture"
            )
            assertThat(scenario.pubsubBodyEntered.await(10, TimeUnit.SECONDS))
                .describedAs("The real pubsub test body should start")
                .isTrue()
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)

            secureChannelRun = startFixture(
                SecureChannelLeakDetectionFixture::class.java,
                "overlappingBodyThatThrows",
                "secure-channel-leak-detection-fixture"
            )
            assertThat(scenario.secureChannelBodyEntered.await(10, TimeUnit.SECONDS))
                .describedAs("The real secure-channel test body should start")
                .isTrue()
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)

            scenario.allowPubsubBodyToExit.countDown()
            pubsubRun.awaitCompletion()
            assertThat(pubsubRun.listener.summary.testsSucceededCount).isEqualTo(1)
            assertThat(pubsubRun.listener.summary.testsFailedCount).isZero()
            assertThat(ResourceLeakDetector.getLevel())
                .describedAs("PARANOID must remain active while the secure-channel test still owns its scope")
                .isEqualTo(ResourceLeakDetector.Level.PARANOID)

            scenario.allowSecureChannelBodyToExit.countDown()
            secureChannelRun.awaitCompletion()
            assertThat(secureChannelRun.listener.summary.testsSucceededCount).isZero()
            assertThat(secureChannelRun.listener.summary.testsFailedCount).isEqualTo(1)
            assertThat(secureChannelRun.listener.summary.failures.single().exception)
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage(DELIBERATE_SECURE_CHANNEL_BODY_FAILURE)
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.ADVANCED)
        } finally {
            scenario.allowPubsubBodyToExit.countDown()
            scenario.allowSecureChannelBodyToExit.countDown()
            pubsubRun?.joinForCleanup()
            secureChannelRun?.joinForCleanup()
            LeakDetectionLifecycleFixtureState.clear(scenario)
            ResourceLeakDetector.setLevel(originalLevel)
        }
    }
}

class PubsubRouterLeakDetectionFixture : PubsubRouterTest(DeterministicFuzz.createFloodFuzzRouterFactory()) {
    @Test
    fun overlappingBody() {
        val scenario = LeakDetectionLifecycleFixtureState.current()
        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
        scenario.pubsubBodyEntered.countDown()
        check(scenario.allowPubsubBodyToExit.await(10, TimeUnit.SECONDS)) {
            "The pubsub leak-detection fixture was not allowed to exit within 10 seconds"
        }
    }
}

class SecureChannelLeakDetectionFixture : SecureChannelTestBase(
    ::PlaintextInsecureChannel,
    emptyList(),
    "/plaintext/2.0.0"
) {
    @Test
    fun overlappingBodyThatThrows() {
        val scenario = LeakDetectionLifecycleFixtureState.current()
        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
        scenario.secureChannelBodyEntered.countDown()
        check(scenario.allowSecureChannelBodyToExit.await(10, TimeUnit.SECONDS)) {
            "The secure-channel leak-detection fixture was not allowed to exit within 10 seconds"
        }
        throw IllegalStateException(DELIBERATE_SECURE_CHANNEL_BODY_FAILURE)
    }
}

private class LeakDetectionLifecycleScenario {
    val pubsubBodyEntered = CountDownLatch(1)
    val secureChannelBodyEntered = CountDownLatch(1)
    val allowPubsubBodyToExit = CountDownLatch(1)
    val allowSecureChannelBodyToExit = CountDownLatch(1)
}

private object LeakDetectionLifecycleFixtureState {
    private val currentScenario = AtomicReference<LeakDetectionLifecycleScenario?>()

    fun install(scenario: LeakDetectionLifecycleScenario) {
        check(currentScenario.compareAndSet(null, scenario)) {
            "A leak-detection lifecycle fixture scenario is already installed"
        }
    }

    fun current(): LeakDetectionLifecycleScenario = checkNotNull(currentScenario.get()) {
        "No leak-detection lifecycle fixture scenario is installed"
    }

    fun clear(scenario: LeakDetectionLifecycleScenario) {
        check(currentScenario.compareAndSet(scenario, null)) {
            "The installed leak-detection lifecycle fixture scenario changed before cleanup"
        }
    }
}

private class FixtureRun(
    val listener: SummaryGeneratingListener,
    private val thread: Thread,
    private val executionFailure: AtomicReference<Throwable?>
) {
    fun awaitCompletion() {
        thread.join(TimeUnit.SECONDS.toMillis(10))
        check(!thread.isAlive) { "Nested JUnit fixture execution did not finish within 10 seconds" }
        executionFailure.get()?.let { throw AssertionError("Nested JUnit fixture execution failed", it) }
    }

    fun joinForCleanup() {
        thread.join(TimeUnit.SECONDS.toMillis(10))
        check(!thread.isAlive) { "Nested JUnit fixture cleanup did not finish within 10 seconds" }
    }
}

private fun startFixture(fixtureClass: Class<*>, methodName: String, threadName: String): FixtureRun {
    val listener = SummaryGeneratingListener()
    val executionFailure = AtomicReference<Throwable?>()
    val fixtureThread = thread(name = threadName) {
        try {
            LauncherFactory.create().execute(
                request()
                    .selectors(selectMethod(fixtureClass, methodName))
                    .build(),
                listener
            )
        } catch (throwable: Throwable) {
            executionFailure.set(throwable)
        }
    }
    return FixtureRun(listener, fixtureThread, executionFailure)
}

private const val DELIBERATE_SECURE_CHANNEL_BODY_FAILURE =
    "Deliberate secure-channel test-body failure used to verify inherited cleanup"
