package priv.kit.ui

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.ui.adb.PrivilegeUiAdbActions
import priv.kit.ui.external.PrivilegeUiExternalStartActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.state.PrivilegeUiViewModelStore

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiEffectsCoordinatorTest {
    @Test
    fun missingPermissionSuspendsPassiveDiscoveryUntilGrantedAndRevocationCancelsIt() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.store.updateState {
                it.copy(selectedStartupMode = PrivilegeUiStartupMode.ADB, localNetworkPermissionMissing = true)
            }
            fixture.effects.setHostResumed("a", true)
            fixture.effects.initialize()
            testScheduler.runCurrent()
            fixture.effects.refreshHostResumeState()
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(0, fixture.wirelessRefreshes)
            assertEquals(0, fixture.wirelessPolls)

            fixture.store.updateState { it.copy(localNetworkPermissionMissing = false) }
            testScheduler.runCurrent()
            assertEquals(1, fixture.wirelessRefreshes)
            assertEquals(1, fixture.wirelessPolls)

            fixture.store.updateState { it.copy(localNetworkPermissionMissing = true) }
            testScheduler.runCurrent()
            assertEquals(1, fixture.wirelessCancellations)
            fixture.effects.setHostResumed("a", false)
            testScheduler.runCurrent()
            fixture.effects.setHostResumed("a", true)
            testScheduler.runCurrent()
            assertEquals(1, fixture.wirelessRefreshes)
            assertEquals(1, fixture.wirelessPolls)

            fixture.store.updateState { it.copy(localNetworkPermissionMissing = false) }
            testScheduler.runCurrent()
            assertEquals(2, fixture.wirelessRefreshes)
            assertEquals(2, fixture.wirelessPolls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun onlyVisibleSelectedPagePollsAndLastHostPauseStopsIt() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.effects.initialize()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(0, fixture.provider.calls)

            fixture.effects.setHostResumed("a", true)
            testScheduler.runCurrent()
            assertEquals(1, fixture.provider.calls)
            fixture.effects.setHostResumed("b", true)
            fixture.effects.setHostResumed("a", false)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            assertEquals(2, fixture.provider.calls)

            fixture.effects.setHostResumed("b", false)
            testScheduler.runCurrent()
            fixture.effects.refreshHostResumeState()
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(2, fixture.provider.calls)

            fixture.effects.setHostResumed("b", true)
            testScheduler.runCurrent()
            assertEquals(3, fixture.provider.calls)
            fixture.store.updateState { it.copy(selectedStartupMode = PrivilegeUiStartupMode.ROOT) }
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(3, fixture.provider.calls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun leavingPageCancelsActiveRefreshAndResumeStartsFresh() = runTest {
        val fixture = Fixture(this)
        fixture.provider.blocker = CompletableDeferred()
        try {
            fixture.effects.setHostResumed("a", true)
            fixture.effects.initialize()
            testScheduler.runCurrent()
            assertEquals(1, fixture.provider.calls)
            fixture.effects.removeHost("a")
            testScheduler.runCurrent()
            assertEquals(1, fixture.provider.cancelled)
            fixture.provider.blocker = null
            fixture.effects.setHostResumed("a", true)
            testScheduler.runCurrent()
            assertEquals(2, fixture.provider.calls)
        } finally {
            fixture.close()
        }
    }

    private class Provider : PrivilegeUiExternalStartProvider {
        override val id = "test"
        override val label = "Test"
        var calls = 0
        var cancelled = 0
        var blocker: CompletableDeferred<Unit>? = null
        override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot {
            calls++
            try {
                blocker?.await()
            } catch (exception: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw exception
            }
            return PrivilegeUiExternalStartSnapshot(available = true, authorized = true)
        }
        override suspend fun start(context: Context, commandLine: String) = Unit
    }

    private class Fixture(scope: TestScope) : AutoCloseable {
        var wirelessRefreshes = 0
        var wirelessPolls = 0
        var wirelessCancellations = 0
        val provider = Provider()
        val store = PrivilegeUiViewModelStore(
            RuntimeEnvironment.getApplication(),
            PrivilegeUiConfig(
                startupModes = listOf(PrivilegeUiStartupMode.EXTERNAL, PrivilegeUiStartupMode.ROOT),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                externalStartProviders = listOf(provider),
                externalStartStatusPollIntervalMillis = 100,
            ),
        )
        private val owner = PrivilegeUiStartGate.newInteractiveOwner()
        private val prompts = PrivilegeUiSystemPromptCoordinator()
        private val runtime = PrivilegeUiRuntimeActions(
            store, scope.backgroundScope, owner::tryAcquire, prompts,
            operationDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        private val adb = PrivilegeUiAdbActions(
            store, runtime, scope.backgroundScope, owner::tryAcquire, { false }, prompts,
        )
        private val external = PrivilegeUiExternalStartActions(
            store, runtime, acquireInteractivePermit = owner::tryAcquire, systemPromptCoordinator = prompts,
        )
        val effects = PrivilegeUiEffectsCoordinator(
            store, owner, runtime, adb, external, scope.backgroundScope,
            refreshWirelessStatus = { wirelessRefreshes++ },
            pollWirelessStatus = {
                wirelessPolls++
                try {
                    awaitCancellation()
                } finally {
                    wirelessCancellations++
                }
            },
        )
        override fun close() {
            effects.close()
            adb.close()
            runtime.close()
            prompts.close()
        }
    }
}
