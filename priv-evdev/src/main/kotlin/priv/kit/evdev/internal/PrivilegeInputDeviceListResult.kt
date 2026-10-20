package priv.kit.evdev.internal

import kotlinx.parcelize.Parcelize

/** Internal same-version wire result. */
@Parcelize
internal class PrivilegeInputDeviceListResult(
    override var error: PrivilegeInputError? = null,
    var paths: List<String> = emptyList(),
) : PrivilegeInputResult
