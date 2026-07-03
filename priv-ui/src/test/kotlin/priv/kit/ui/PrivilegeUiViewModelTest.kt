package priv.kit.ui

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiViewModelTest {
    @Test
    fun defaultConfigOrdersTabsButSelectsAdb() {
        val application = RuntimeEnvironment.getApplication() as Application
        installRuntimeContext(application)
        val viewModel = ConfiguredPrivilegeUiViewModel(
            application = application,
            config = PrivilegeUiConfig(
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
        val application = RuntimeEnvironment.getApplication() as Application
        installRuntimeContext(application)
        val viewModel = ConfiguredPrivilegeUiViewModel(
            application = application,
            config = PrivilegeUiConfig(
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
        val application = RuntimeEnvironment.getApplication() as Application
        installRuntimeContext(application)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)

        val state = viewModel.state.value

        assertEquals(listOf(PrivilegeUiStartupMode.ROOT), state.startupModes)
        assertEquals(PrivilegeUiStartupMode.ROOT, state.selectedStartupMode)
    }

    @Test
    @Config(sdk = [29])
    fun android10DoesNotStartWirelessAdbStatusPolling() {
        val application = RuntimeEnvironment.getApplication() as Application
        installRuntimeContext(application)
        val viewModel = ConfiguredPrivilegeUiViewModel(
            application = application,
            config = PrivilegeUiConfig(
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
    fun adbStartupTabSelectionIsHeldByViewModel() {
        val application = RuntimeEnvironment.getApplication() as Application
        installRuntimeContext(application)
        val viewModel = ConfiguredPrivilegeUiViewModel(
            application = application,
            config = PrivilegeUiConfig(
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )

        try {
            viewModel.selectAdbStartupTab(PrivilegeUiAdbStartupTab.STATIC_TCP)

            assertEquals(PrivilegeUiAdbStartupTab.STATIC_TCP, viewModel.selectedAdbStartupTab.value)
        } finally {
            viewModel.stopTcpModeStatusPolling()
        }
    }

    @Test
    fun tcpModeStatusPollingFollowsAdbMode() {
        val application = RuntimeEnvironment.getApplication() as Application
        installRuntimeContext(application)
        val viewModel = ConfiguredPrivilegeUiViewModel(
            application = application,
            config = PrivilegeUiConfig(
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

            viewModel.selectStartupMode(PrivilegeUiStartupMode.ROOT)

            assertFalse(viewModel.state.value.tcpModeStatusPollingActive)

            viewModel.selectStartupMode(PrivilegeUiStartupMode.ADB)

            assertTrue(viewModel.state.value.tcpModeStatusPollingActive)
        } finally {
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

    private fun installRuntimeContext(context: Context) {
        val runtimeContext = Class.forName("priv.kit.internal.runtime.PrivilegeContext")
        val instance = runtimeContext.getField("INSTANCE").get(null)
        runtimeContext.getDeclaredMethod("install", Context::class.java).invoke(instance, context)
    }
}
