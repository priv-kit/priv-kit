package priv.kit.evdev.internal

import kotlinx.parcelize.Parcelize

/** Internal same-version wire result. */
@Parcelize
internal class PrivilegeInputNameResult(
    override var error: PrivilegeInputError? = null,
    var name: String = "",
) : PrivilegeInputResult
