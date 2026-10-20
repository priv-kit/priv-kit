package priv.kit.evdev.internal

import android.hardware.display.DisplayManager
import android.os.Binder
import android.view.DisplayHidden
import android.view.DisplayInfo
import priv.kit.evdev.PrivilegeEvdevTouchMapping
import priv.kit.evdev.PrivilegeEvdevTargetException

/** One atomic display snapshot, without app-window or compatibility dimensions. */
internal fun readTouchMapping(displays: DisplayManager, displayId: Int): PrivilegeEvdevTouchMapping {
    val identity = Binder.clearCallingIdentity()
    try {
        val display = displays.getDisplay(displayId) ?: throw PrivilegeEvdevTargetException(displayId, "Display $displayId is unavailable")
        val info = DisplayInfo()
        if (!(display as DisplayHidden).getDisplayInfo(info)) throw PrivilegeEvdevTargetException(displayId, "Display $displayId was removed")
        return PrivilegeEvdevTouchMapping(info.logicalWidth, info.logicalHeight, info.rotation)
    } finally {
        Binder.restoreCallingIdentity(identity)
    }
}

internal fun readDisplayState(displays: DisplayManager, displayId: Int): Int {
    val identity = Binder.clearCallingIdentity()
    try {
        return displays.getDisplay(displayId)?.state ?: throw PrivilegeEvdevTargetException(displayId, "Display $displayId is unavailable")
    } finally {
        Binder.restoreCallingIdentity(identity)
    }
}
