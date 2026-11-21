package priv.kit.ui.m3

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeM3AccessRecreationTest {
    class Model(application: Application) : PrivilegeM3ViewModel(application,
        PrivilegeUiConfig(startupModes = listOf(PrivilegeUiStartupMode.ROOT))) {
        // Isolate the default flow and Activity bridge; coordinator lifetime is tested in priv-ui.
        suspend fun requestBatteryForTest() = accessHandler.request(PrivilegeUiAccessRequest.BatteryOptimization)
    }

    class HostActivity : ComponentActivity() {
        lateinit var model: Model
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            model = ViewModelProvider(this)[Model::class.java]
            PrivilegeUiAccessHost(this, model)
        }
    }

    @Test
    fun batteryExplanationAndSettingsWaitSurviveActivityRecreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val application = RuntimeEnvironment.getApplication()
        PrivilegeContext.install(application)
        val power = shadowOf(application.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(application.packageName, false)
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val model = controller.get().model
            val request = model.viewModelScope.async {
                model.requestBatteryForTest()
            }
            runCurrent()
            assertEquals(PrivilegeM3AccessExplanation.BATTERY_OPTIMIZATION, model.m3State.accessExplanation)
            controller.configurationChange(Configuration(controller.get().resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            })
            runCurrent()
            assertSame(model, controller.get().model)
            assertFalse(request.isCompleted)
            assertEquals(PrivilegeM3AccessExplanation.BATTERY_OPTIMIZATION, model.m3State.accessExplanation)
            model.m3Actions.answerAccessExplanation(true)
            runCurrent()
            val started = shadowOf(controller.get()).nextStartedActivityForResult
            assertNotNull(started)
            assertFalse(request.isCompleted)
            controller.configurationChange(Configuration(controller.get().resources.configuration).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
            })
            runCurrent()
            assertSame(model, controller.get().model)
            assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
            assertFalse(request.isCompleted)
            power.setIgnoringBatteryOptimizations(application.packageName, true)
            controller.get().activityResultRegistry.dispatchResult(started.requestCode, Activity.RESULT_OK, null)
            runCurrent()
            assertTrue(request.await())
            assertNull(model.m3State.accessExplanation)
        } finally {
            controller.pause().stop().destroy()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun finishingActivityCancelsVmOwnedExplanation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val application = RuntimeEnvironment.getApplication()
        PrivilegeContext.install(application)
        shadowOf(application.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(application.packageName, false)
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val model = controller.get().model
            val request = model.viewModelScope.async {
                model.requestBatteryForTest()
            }
            runCurrent()
            assertNotNull(model.m3State.accessExplanation)
            controller.get().finish()
            controller.pause().stop().destroy()
            runCurrent()
            assertTrue(request.isCancelled)
            assertNull(model.m3State.accessExplanation)
        } finally { Dispatchers.resetMain() }
    }
}
