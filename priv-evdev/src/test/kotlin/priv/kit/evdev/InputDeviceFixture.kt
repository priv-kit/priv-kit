package priv.kit.evdev

import priv.kit.evdev.internal.InputDeviceAccess

import android.view.Display
import priv.kit.evdev.internal.InputChanges
import priv.kit.evdev.internal.PrivilegeEvdevBackend

internal fun PrivilegeEvdevController.Companion.createLocal(
    backend: PrivilegeEvdevBackend?,
    list: () -> List<String> = { emptyList() },
    displayMapping: (Int) -> PrivilegeEvdevTouchMapping = { error("No test display mapping") },
    touchDevices: () -> List<PrivilegeEvdevTouchDeviceInfo> = { error("No test devices") },
    startChanges: () -> InputChanges = { error("No test monitor") },
    displayState: (Int) -> Int = { Display.STATE_ON },
): PrivilegeEvdevController = createLocal(object : InputDeviceAccess() {
    override val native get() = checkNotNull(backend) { "Unexpected native access" }
    override fun list() = list.invoke()
    override fun displayMapping(displayId: Int) = displayMapping.invoke(displayId)
    override fun touchDevices() = touchDevices.invoke()
    override fun startChanges() = startChanges.invoke()
    override fun displayState(displayId: Int) = displayState.invoke(displayId)
})
