package priv.kit.evdev

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import priv.kit.evdev.internal.PrivilegeInputChanges
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34, 36], manifest = Config.NONE, shadows = [PrivilegeInputChangesTest.Displays::class])
class PrivilegeInputChangesTest {
    @Implements(DisplayManager::class)
    class Displays {
        @Implementation
        fun registerDisplayListener(listener: DisplayManager.DisplayListener, handler: Handler?) {
            legacyRegistrations++
        }
        @Implementation(minSdk = 36)
        fun registerDisplayListener(executor: Executor, flags: Long, listener: DisplayManager.DisplayListener) {
            eventFlags = flags
        }
        @Implementation
        fun unregisterDisplayListener(listener: DisplayManager.DisplayListener) {
            unregisters++
            if (failUnregister) throw IllegalStateException("unregister failed")
        }
    }
    companion object {
        var legacyRegistrations = 0
        var eventFlags = 0L
        var unregisters = 0
        var failUnregister = false
    }
    @Before fun reset() {
        legacyRegistrations = 0
        eventFlags = 0
        unregisters = 0
        failUnregister = false
    }
    @Test fun subscribesToStateOnNewAndroidAndFiltersUnrelatedChanges() {
        PrivilegeInputChanges(RuntimeEnvironment.getApplication()).use { changes ->
            if (Build.VERSION.SDK_INT >= 36) {
                assertEquals(0, legacyRegistrations)
                assertTrue(eventFlags and DisplayManager.EVENT_TYPE_DISPLAY_STATE != 0L)
                assertEquals(0L, eventFlags and DisplayManager.EVENT_TYPE_DISPLAY_REFRESH_RATE)
            } else {
                assertEquals(1, legacyRegistrations)
                assertEquals(0L, eventFlags)
            }
            changes.onInputDeviceAdded(88)
            changes.onDisplayRemoved(9)
            changes.onDisplayChanged(0) // Refresh-rate changes must not invalidate the active contact.
            assertEquals(3L, changes.version)
            assertEquals(0L, changes.touchVersion(0, 7))
            changes.onInputDeviceRemoved(7)
            assertEquals(4L, changes.touchVersion(0, 7))
            changes.onDisplayRemoved(0)
            assertEquals(5L, changes.touchVersion(0, 7))
        }
        assertEquals(1, unregisters)
    }
    @Test fun failedUnregisterCanBeRetriedWithoutRepeatingSuccessfulCleanup() {
        val changes = PrivilegeInputChanges(RuntimeEnvironment.getApplication())
        failUnregister = true
        assertThrows(IllegalStateException::class.java) { changes.close() }
        failUnregister = false
        changes.close()
        changes.close()
        assertEquals(2, unregisters)
    }
}
