package io.libp2p.pubsub

import io.libp2p.pubsub.flood.FloodPubsubRouterTest
import io.libp2p.security.tls.TlsSecureChannelTest
import io.libp2p.tools.ResourceLeakDetectorLevelScope
import io.netty.util.ResourceLeakDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
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
        val outerScope = ResourceLeakDetectorLevelScope(ResourceLeakDetector.Level.PARANOID)
        val scenario = LeakDetectionLifecycleScenario()
        var outerScopeEnabled = false
        var fixtureStateInstalled = false
        var pubsubRun: FixtureRun? = null
        var secureChannelRun: FixtureRun? = null
        var primaryFailure: Throwable? = null
        try {
            outerScope.enable()
            outerScopeEnabled = true
            LeakDetectionLifecycleFixtureState.install(scenario)
            fixtureStateInstalled = true
            pubsubRun = startFixture(
                FloodPubsubRouterTest::class.java,
                "Fanout",
                "pubsub-leak-detection-fixture"
            )
            assertThat(scenario.pubsubBodyEntered.await(10, TimeUnit.SECONDS))
                .describedAs("The real pubsub test body should start")
                .isTrue()
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)

            secureChannelRun = startFixture(
                TlsSecureChannelTest::class.java,
                "incorrect initiator remote PeerId should throw",
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
            assertThat(ResourceLeakDetector.getLevel())
                .describedAs("The outer scope must retain PARANOID after both nested lifecycles restore")
                .isEqualTo(ResourceLeakDetector.Level.PARANOID)
        } catch (throwable: Throwable) {
            primaryFailure = throwable
        } finally {
            scenario.allowPubsubBodyToExit.countDown()
            scenario.allowSecureChannelBodyToExit.countDown()
            primaryFailure = captureCleanupFailure(primaryFailure) { pubsubRun?.joinForCleanup() }
            primaryFailure = captureCleanupFailure(primaryFailure) { secureChannelRun?.joinForCleanup() }
            if (fixtureStateInstalled) {
                primaryFailure = captureCleanupFailure(primaryFailure) {
                    LeakDetectionLifecycleFixtureState.clear(scenario)
                }
            }
            if (outerScopeEnabled) {
                primaryFailure = captureCleanupFailure(primaryFailure) {
                    outerScope.restore()
                }
            }
            primaryFailure = captureCleanupFailure(primaryFailure) {
                assertThat(ResourceLeakDetector.getLevel())
                    .describedAs("The outer scope must restore the leak-detection level captured before the test")
                    .isEqualTo(originalLevel)
            }
        }
        primaryFailure?.let { throw it }
    }
}

class LeakDetectionLifecycleExtension : BeforeTestExecutionCallback, AfterTestExecutionCallback {
    override fun beforeTestExecution(context: ExtensionContext) {
        val scenario = LeakDetectionLifecycleFixtureState.currentOrNull() ?: return
        when (context.requiredTestClass) {
            FloodPubsubRouterTest::class.java -> {
                assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
                scenario.pubsubBodyEntered.countDown()
                check(scenario.allowPubsubBodyToExit.await(10, TimeUnit.SECONDS)) {
                    "The pubsub leak-detection fixture was not allowed to exit within 10 seconds"
                }
            }
            TlsSecureChannelTest::class.java -> {
                assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID)
                scenario.secureChannelBodyEntered.countDown()
                check(scenario.allowSecureChannelBodyToExit.await(10, TimeUnit.SECONDS)) {
                    "The secure-channel leak-detection fixture was not allowed to exit within 10 seconds"
                }
            }
            else -> throw IllegalStateException(
                "The leak-detection lifecycle extension does not support ${context.requiredTestClass.name}"
            )
        }
    }

    override fun afterTestExecution(context: ExtensionContext) {
        if (
            LeakDetectionLifecycleFixtureState.currentOrNull() != null &&
            context.requiredTestClass == TlsSecureChannelTest::class.java
        ) {
            throw IllegalStateException(DELIBERATE_SECURE_CHANNEL_BODY_FAILURE)
        }
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

    fun currentOrNull(): LeakDetectionLifecycleScenario? = currentScenario.get()

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
                    .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                    .build(),
                listener
            )
        } catch (throwable: Throwable) {
            executionFailure.set(throwable)
        }
    }
    return FixtureRun(listener, fixtureThread, executionFailure)
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

private const val DELIBERATE_SECURE_CHANNEL_BODY_FAILURE =
    "Deliberate secure-channel test-body completion failure used to verify inherited cleanup"
