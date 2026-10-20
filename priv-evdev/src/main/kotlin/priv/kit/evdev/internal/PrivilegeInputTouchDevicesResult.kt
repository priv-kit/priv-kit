package priv.kit.evdev.internal

import kotlinx.parcelize.Parcelize
import priv.kit.evdev.PrivilegeEvdevTouchDeviceInfo

@Parcelize
internal class PrivilegeInputTouchDevicesResult(
    override var error: PrivilegeInputError? = null,
    var devices: List<PrivilegeEvdevTouchDeviceInfo> = emptyList(),
) : PrivilegeInputResult
