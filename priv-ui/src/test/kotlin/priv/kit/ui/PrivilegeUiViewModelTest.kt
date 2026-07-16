package priv.kit.ui

import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.external.*
import priv.kit.ui.state.*

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiViewModelTest {
    @Test
    fun defaultConfigOrdersTabsButSelectsAdb() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )

        val state = viewModel.state.value

        assertEquals(
            listOf(
                PrivilegeUiStartupMode.ROOT,
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.MANUAL_SHELL,
            ),
            state.startupModes,
        )
        assertEquals(PrivilegeUiStartupMode.ADB, state.selectedStartupMode)
    }

    @Test
    fun externalModeIsLastAndAdbRemainsSelected() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                externalStartProviders = listOf(TestExternalStartProvider),
            ),
        )

        val state = viewModel.state.value

        assertEquals(
            listOf(
                PrivilegeUiStartupMode.ROOT,
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.MANUAL_SHELL,
                PrivilegeUiStartupMode.EXTERNAL,
            ),
            state.startupModes,
        )
        assertEquals(PrivilegeUiStartupMode.ADB, state.selectedStartupMode)
    }

    @Test
    fun constructorConfigInitializesState() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())

        val state = viewModel.state.value

        assertEquals(listOf(PrivilegeUiStartupMode.ROOT), state.startupModes)
        assertEquals(PrivilegeUiStartupMode.ROOT, state.selectedStartupMode)
    }

    @Test
    @Config(sdk = [29])
    fun android10DoesNotStartWirelessAdbStatusPolling() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ADB),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )

        assertFalse(viewModel.state.value.wirelessStatusPollingActive)

        viewModel.startWirelessAdbStatusPolling()
        viewModel.onHostResume()

        assertFalse(viewModel.state.value.wirelessStatusPollingActive)
    }

    @Test
    fun directAdbStartIncludesWirelessWithoutCachedReadiness() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        val store = viewModel.storeForTest()
        store.updateState {
            it.copy(
                wifiConnected = false,
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.UNKNOWN,
            )
        }

        assertEquals(
            PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
            viewModel.adbActionsForTest().directStartAttempts().single().runtimeStartSource,
        )
    }

    @Test
    fun directAdbStartUsesConfiguredTcpWithoutCachedAuthorization() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        val store = viewModel.storeForTest()
        val adbActions = viewModel.adbActionsForTest()
        store.config = store.config.copy(adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING)
        store.updateState {
            it.copy(
                wifiConnected = false,
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            )
        }

        val attempts = adbActions.directStartAttempts()

        assertTrue(attempts.first() is PrivilegeUiRuntimeStartAttempt.Workflow)
        assertEquals(
            listOf(
                PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
                PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
            ),
            attempts.map { it.runtimeStartSource },
        )
    }

    @Test
    fun publicStartAndCancelCommandsAreIgnoredWhileCancelling() = runBlocking {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ROOT),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                startTimeoutMillis = 250L,
            ),
        )
        val actions = viewModel.runtimeActionsForTest()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val startCount = AtomicInteger(0)
        try {
            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "external requested",
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                ) {
                    startCount.incrementAndGet()
                    started.countDown()
                    release.await(30, TimeUnit.SECONDS)
                },
            )

            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(1, startCount.get())
            assertEquals(PrivilegeUiRuntimeStartPhase.RUNNING, viewModel.state.value.runtimeStartPhase)

            viewModel.stopCurrentStart()

            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, viewModel.state.value.runtimeStartPhase)

            viewModel.startAvailable()
            viewModel.stopCurrentStart()

            assertEquals(1, startCount.get())
            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, viewModel.state.value.runtimeStartPhase)

            release.countDown()
            assertTrue(
                withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
                    viewModel.state.first { state ->
                        state.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE && !state.busy
                    }
                    true
                } == true,
            )
        } finally {
            release.countDown()
            actions.close()
        }
    }

    @Test
    fun hostResumeKeepsPendingTcpAuthorizationRequestAlive() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        val store = viewModel.storeForTest()
        val request = CloseCounter()
        store.tcpAuthorizationRequest = request
        store.updateState {
            it.copy(
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
            )
        }

        viewModel.onHostResume()

        assertEquals(0, request.closeCount)
        assertEquals(request, store.tcpAuthorizationRequest)
        assertEquals(
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
            viewModel.state.value.tcpAuthorizationStatus,
        )
    }

    @Test
    fun tcpModeStatusPollingFollowsAdbMode() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = setOf(
                    PrivilegeUiStartupMode.ADB,
                    PrivilegeUiStartupMode.ROOT,
                ),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
                wirelessStatusPollIntervalMillis = 60_000L,
            ),
        )

        try {
            assertTrue(viewModel.state.value.tcpModeStatusPollingActive)

            viewModel.onHostResume()
            viewModel.selectStartupMode(PrivilegeUiStartupMode.ROOT)

            assertFalse(viewModel.state.value.tcpModeStatusPollingActive)

            viewModel.selectStartupMode(PrivilegeUiStartupMode.ADB)

            assertTrue(viewModel.state.value.tcpModeStatusPollingActive)
        } finally {
            viewModel.stopTcpModeStatusPolling()
            viewModel.stopWirelessAdbStatusPolling()
        }
    }

    @Test
    fun tcpModeStatusPollingHandleKeepsPollingUntilAllHandlesClose() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ROOT),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
                wirelessStatusPollIntervalMillis = 60_000L,
            ),
        )
        var first: AutoCloseable? = null
        var second: AutoCloseable? = null

        try {
            assertFalse(viewModel.state.value.tcpModeStatusPollingActive)

            first = viewModel.startTcpModeStatusPolling()
            second = viewModel.startTcpModeStatusPolling()

            assertTrue(viewModel.state.value.tcpModeStatusPollingActive)

            first.close()

            assertTrue(viewModel.state.value.tcpModeStatusPollingActive)

            second.close()

            assertFalse(viewModel.state.value.tcpModeStatusPollingActive)
        } finally {
            second?.close()
            first?.close()
            viewModel.stopTcpModeStatusPolling()
        }
    }

    @Test
    fun stopTcpModeStatusPollingStopsAllHandles() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ROOT),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
                wirelessStatusPollIntervalMillis = 60_000L,
            ),
        )
        var first: AutoCloseable? = null
        var second: AutoCloseable? = null

        try {
            first = viewModel.startTcpModeStatusPolling()
            second = viewModel.startTcpModeStatusPolling()

            assertTrue(viewModel.state.value.tcpModeStatusPollingActive)

            viewModel.stopTcpModeStatusPolling()

            assertFalse(viewModel.state.value.tcpModeStatusPollingActive)

            first.close()
            second.close()

            assertFalse(viewModel.state.value.tcpModeStatusPollingActive)
        } finally {
            second?.close()
            first?.close()
            viewModel.stopTcpModeStatusPolling()
        }
    }

    private class RootOnlyPrivilegeUiViewModel(
        application: Application,
    ) : PrivilegeUiViewModel(
        application = application,
        config = PrivilegeUiConfig(
            startupModes = setOf(PrivilegeUiStartupMode.ROOT),
            adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
        ),
    )

    private class ConfiguredPrivilegeUiViewModel(
        application: Application,
        config: PrivilegeUiConfig,
    ) : PrivilegeUiViewModel(
        application = application,
        config = config,
    )

    private object TestExternalStartProvider : PrivilegeUiExternalStartProvider {
        override val id: String = "test"
        override val label: CharSequence = "Test"

        override fun start(
            context: Context,
            commandLine: String,
        ) = Unit
    }

    private fun configuredViewModel(config: PrivilegeUiConfig): ConfiguredPrivilegeUiViewModel =
        ConfiguredPrivilegeUiViewModel(
            application = application(),
            config = config,
        )

    private fun application(): Application =
        (RuntimeEnvironment.getApplication() as Application).also(::installRuntimeContext)

    private fun installRuntimeContext(context: Context) {
        val runtimeContext = Class.forName("priv.kit.internal.runtime.PrivilegeContext")
        val instance = runtimeContext.getField("INSTANCE").get(null)
        runtimeContext.getDeclaredMethod("install", Context::class.java).invoke(instance, context)
    }

    private fun PrivilegeUiViewModel.storeForTest(): PrivilegeUiViewModelStore {
        val field = PrivilegeUiViewModel::class.java.getDeclaredField("store")
        field.isAccessible = true
        return field.get(this) as PrivilegeUiViewModelStore
    }

    private fun PrivilegeUiViewModel.adbActionsForTest(): PrivilegeUiAdbActions {
        val field = PrivilegeUiViewModel::class.java.getDeclaredField("adbActions")
        field.isAccessible = true
        return field.get(this) as PrivilegeUiAdbActions
    }

    private fun PrivilegeUiViewModel.runtimeActionsForTest(): PrivilegeUiRuntimeActions {
        val field = PrivilegeUiViewModel::class.java.getDeclaredField("runtimeActions")
        field.isAccessible = true
        return field.get(this) as PrivilegeUiRuntimeActions
    }

    private class CloseCounter : Closeable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }
}
