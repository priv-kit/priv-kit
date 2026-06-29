package priv.kit.ui

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiViewModelTest {
    @Test
    fun constructorConfigInitializesState() {
        val application = RuntimeEnvironment.getApplication() as Application
        installRuntimeContext(application)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)

        val state = viewModel.state.value

        assertEquals(listOf(PrivilegeUiStartupMode.ROOT), state.startupModes)
        assertEquals(PrivilegeUiStartupMode.ROOT, state.selectedStartupMode)
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

    private fun installRuntimeContext(context: Context) {
        val runtimeContext = Class.forName("priv.kit.internal.runtime.PrivilegeRuntimeContext")
        val instance = runtimeContext.getField("INSTANCE").get(null)
        runtimeContext.getDeclaredMethod("install", Context::class.java).invoke(instance, context)
    }
}
