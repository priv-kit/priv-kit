package priv.kit.ui

import android.Manifest
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeUiAccessHostTest {
    private class Registry : ActivityResultRegistry() {
        val launches = mutableListOf<Pair<Int, Any?>>()
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += code to input
        }
    }

    @Test
    fun permissionResultDeliveredBeforeRebindResumesOriginalRequestWithoutRelaunch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val second = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val commands = PrivilegeUiAccessCommands()
        val registry = Registry()
        var host: PrivilegeUiAccessHost? = null
        try {
            shadowOf(first.get()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val waiting = async { commands.permission(Manifest.permission.POST_NOTIFICATIONS) }
            host = PrivilegeUiAccessHost(first.get(), commands, registry)
            runCurrent()
            assertEquals(1, registry.launches.size)
            val code = registry.launches.single().first
            val saved = Bundle().also(registry::onSaveInstanceState)
            host.close()
            runCurrent()
            assertFalse(waiting.isCompleted)
            val replacement = Registry().apply { onRestoreInstanceState(saved) }
            shadowOf(second.get()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            replacement.dispatchResult(code, true)
            host = PrivilegeUiAccessHost(second.get(), commands, replacement)
            runCurrent()
            assertEquals(PrivilegeUiPermissionState.Granted, waiting.await())
            assertTrue(replacement.launches.isEmpty())
        } finally {
            host?.close(); commands.cancelPending()
            first.pause().stop().destroy(); second.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun settingsReturnSurvivesBridgeReplacementAndCancellationIgnoresLateResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val second = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val commands = PrivilegeUiAccessCommands()
        val registry = Registry()
        var host: PrivilegeUiAccessHost? = null
        try {
            val waiting = async { commands.openSettings(PrivilegeUiSettingsDestination.BATTERY_OPTIMIZATION) }
            host = PrivilegeUiAccessHost(first.get(), commands, registry)
            runCurrent()
            assertEquals(1, registry.launches.size)
            val code = registry.launches.single().first
            val saved = Bundle().also(registry::onSaveInstanceState)
            host.close()
            runCurrent()
            val replacement = Registry().apply { onRestoreInstanceState(saved) }
            host = PrivilegeUiAccessHost(second.get(), commands, replacement)
            runCurrent()
            assertFalse(waiting.isCompleted)
            assertTrue(replacement.launches.isEmpty())
            replacement.dispatchResult(code, Activity.RESULT_OK, null)
            assertTrue(waiting.await())

            val cancelled = async { commands.openSettings(PrivilegeUiSettingsDestination.APPLICATION_PERMISSIONS) }
            runCurrent()
            val oldCode = replacement.launches.last().first
            commands.cancelPending()
            kotlin.test.assertFailsWith<CancellationException> { cancelled.await() }
            val next = async { commands.openSettings(PrivilegeUiSettingsDestination.NOTIFICATIONS) }
            runCurrent()
            replacement.dispatchResult(oldCode, Activity.RESULT_OK, null)
            runCurrent()
            assertFalse(next.isCompleted)
            replacement.dispatchResult(replacement.launches.last().first, Activity.RESULT_OK, null)
            assertTrue(next.await())
        } finally {
            host?.close(); commands.cancelPending()
            first.pause().stop().destroy(); second.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun commandWaitingForNewActivityLaunchesOnceAfterRebind() = runTest {
        val commands = PrivilegeUiAccessCommands()
        val waiting = async { commands.openSettings(PrivilegeUiSettingsDestination.NOTIFICATIONS) }
        runCurrent()
        val command = commands.pending.value!!
        assertTrue(commands.takeForLaunch(command))
        assertFalse(commands.takeForLaunch(command))
        waiting.cancelAndJoin()
        assertNull(commands.pending.value)
        assertFalse(commands.takeForLaunch(command))
    }
}
