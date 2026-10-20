package priv.kit.input.internal

import android.view.Display
import android.view.InputDevice

internal const val DEFAULT_SWIPE_DURATION_MILLIS = 300L

// InputManager's hidden INJECT_INPUT_EVENT_MODE_* constants.
internal const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
internal const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2

internal fun resolveInputSource(source: Int, default: Int): Int =
    if (source == InputDevice.SOURCE_UNKNOWN) default else source

internal fun isPointerSource(source: Int): Boolean =
    source and InputDevice.SOURCE_CLASS_POINTER != 0

internal fun normalizeDisplayId(displayId: Int): Int =
    if (displayId == Display.INVALID_DISPLAY) Display.INVALID_DISPLAY
    else maxOf(Display.DEFAULT_DISPLAY, displayId)

internal fun motionDisplayId(source: Int, displayId: Int): Int =
    if (displayId == Display.INVALID_DISPLAY && isPointerSource(source)) Display.DEFAULT_DISPLAY
    else displayId
