package priv.kit.evdev

import android.hardware.display.DisplayManager
import android.graphics.Point
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.evdev.internal.readTouchMapping

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], manifest = Config.NONE)
class PrivilegeInputDisplaysTest {
    @Suppress("DEPRECATION")
    @Test fun readsLogicalDisplayAndRejectsMissingDisplay() {
        val displays = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
        val display = displays.getDisplay(0)
        val size = Point()
        display.getRealSize(size)
        assertEquals(PrivilegeEvdevTouchMapping(size.x, size.y, display.rotation), readTouchMapping(displays, 0))
        val error = assertThrows(PrivilegeEvdevTargetException::class.java) { readTouchMapping(displays, 99999) }
        assertEquals(99999, error.displayId)
    }
}
