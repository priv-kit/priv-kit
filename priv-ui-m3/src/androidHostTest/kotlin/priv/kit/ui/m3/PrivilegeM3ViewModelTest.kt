package priv.kit.ui.m3

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeM3ViewModelTest {
    class Model(application: Application) : PrivilegeM3ViewModel(application) {
        suspend fun explainForTest() = explainAccess(PrivilegeM3AccessExplanation.NOTIFICATIONS)
    }

    @Test
    fun authorizationUpdatesPresentationWithoutCompose() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val application = RuntimeEnvironment.getApplication()
            PrivilegeContext.install(application)
            val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
                .get(PrivilegeM3ViewModel::class.java)
            val coordinator = PrivilegeUiViewModel::class.java.getDeclaredField("authorizationCoordinator")
                .apply { isAccessible = true }.get(model)
            @Suppress("UNCHECKED_CAST")
            val authorization = coordinator.javaClass.getDeclaredField("current")
                .apply { isAccessible = true }.get(coordinator) as
                kotlinx.coroutines.flow.MutableStateFlow<PrivilegeUiAuthorization?>
            val pending = PrivilegeUiAuthorization(PrivilegeUiAuthorizationKind.ROOT)
            model.updateHostVisibility(resumed = true, focused = false)
            authorization.value = pending
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(pending, model.m3State.visibleAuthorization)
            authorization.value = null
            runCurrent()
            assertNull(model.m3State.visibleAuthorization)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun defaultFactoryCreatesOneRetainedPageModelAndClearCancelsExplanation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val application = RuntimeEnvironment.getApplication()
            PrivilegeContext.install(application)
            val provider = ViewModelProvider(
                store, ViewModelProvider.AndroidViewModelFactory(application),
            )
            val model = provider[Model::class.java]
            val business: PrivilegeUiViewModel = model
            model.m3Actions.selectStartupMode(PrivilegeUiStartupMode.ROOT)
            assertSame(model, provider[Model::class.java])
            assertEquals(PrivilegeUiStartupMode.ROOT, model.m3State.selectedStartupMode)
            assertTrue(business.state.value.startupModes.contains(PrivilegeUiStartupMode.ROOT))
            val waiting = async { model.explainForTest() }
            runCurrent()
            assertEquals(PrivilegeM3AccessExplanation.NOTIFICATIONS, model.m3State.accessExplanation)
            store.clear()
            runCurrent()
            assertTrue(waiting.isCancelled)
            assertNull(model.m3State.accessExplanation)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
