package priv.kit.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiBatteryOptimizationTest {
    @Test
    fun promptVisibilityReflectsBatteryOptimizationExemption() {
        val application = application()
        val powerManager = application.getSystemService(PowerManager::class.java)
        val shadowPowerManager = shadowOf(powerManager)

        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)
        assertTrue(application.isPrivilegeUiBatteryOptimizationPromptVisible())

        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)
        assertFalse(application.isPrivilegeUiBatteryOptimizationPromptVisible())
    }

    @Test
    fun requestActionDirectlyTargetsHostPackageInANewTask() {
        val application = application()

        assertTrue(application.requestPrivilegeUiBatteryOptimizationExemption())

        val intent = shadowOf(application).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package", intent.data?.scheme)
        assertEquals(application.packageName, intent.data?.schemeSpecificPart)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun requestActionDoesNotCreateANewTaskFromAnActivityContext() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val context = RecordingSettingsContext(controller.get())

        try {
            assertTrue(context.requestPrivilegeUiBatteryOptimizationExemption())

            assertEquals(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                context.startedIntent?.action,
            )
            assertFalse(
                context.startedIntent?.flags?.and(Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [28, 36])
    fun requestActionSkipsDirectRequestWhenPermissionIsRemovedFromMergedManifest() {
        val application = application()
        val packageInfo = application.packageManager
            .getPackageInfo(application.packageName, 0)
            .apply {
                requestedPermissions = requestedPermissions
                    ?.filterNot {
                        it == Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    }
                    ?.toTypedArray()
                    ?: emptyArray()
            }
        shadowOf(application.packageManager).installPackage(packageInfo)
        val context = RecordingSettingsContext(application)

        assertTrue(context.requestPrivilegeUiBatteryOptimizationExemption())

        assertEquals(
            listOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            context.attemptedActions,
        )
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            context.startedIntent?.action,
        )
    }

    @Test
    fun requestActionFallsBackToBatteryOptimizationSettings() {
        val application = application()
        val context = RecordingSettingsContext(
            base = application,
            activityNotFoundActions = setOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            ),
        )

        assertTrue(context.requestPrivilegeUiBatteryOptimizationExemption())

        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            ),
            context.attemptedActions,
        )
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            context.startedIntent?.action,
        )
    }

    @Test
    fun requestActionFallsBackToApplicationDetails() {
        val application = application()
        val context = RecordingSettingsContext(
            base = application,
            activityNotFoundActions = setOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            ),
        )

        assertTrue(context.requestPrivilegeUiBatteryOptimizationExemption())

        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            context.attemptedActions,
        )
        assertEquals("package", context.startedIntent?.data?.scheme)
        assertEquals(application.packageName, context.startedIntent?.data?.schemeSpecificPart)
    }

    @Test
    fun requestActionFallsBackAfterSecurityException() {
        val context = RecordingSettingsContext(
            base = application(),
            securityExceptionActions = setOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            ),
        )

        assertTrue(context.requestPrivilegeUiBatteryOptimizationExemption())

        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            ),
            context.attemptedActions,
        )
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            context.startedIntent?.action,
        )
    }

    @Test
    fun requestActionReturnsFalseWhenNoSupportedActivityCanOpen() {
        val actions = setOf(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )
        val context = RecordingSettingsContext(
            base = application(),
            activityNotFoundActions = actions,
        )

        assertFalse(context.requestPrivilegeUiBatteryOptimizationExemption())
        assertEquals(actions.toList(), context.attemptedActions)
    }

    private fun application(): Application =
        RuntimeEnvironment.getApplication() as Application

    private class RecordingSettingsContext(
        base: Context,
        private val activityNotFoundActions: Set<String> = emptySet(),
        private val securityExceptionActions: Set<String> = emptySet(),
    ) : ContextWrapper(base) {
        val attemptedActions = mutableListOf<String?>()
        var startedIntent: Intent? = null
            private set

        override fun startActivity(intent: Intent) {
            val action = intent.action
            attemptedActions += action
            when {
                action != null && action in activityNotFoundActions ->
                    throw ActivityNotFoundException()
                action != null && action in securityExceptionActions ->
                    throw SecurityException()
            }
            startedIntent = Intent(intent)
        }
    }
}
