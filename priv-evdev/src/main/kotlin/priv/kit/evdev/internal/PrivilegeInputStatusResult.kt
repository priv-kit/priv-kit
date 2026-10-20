package priv.kit.evdev.internal

import kotlinx.parcelize.Parcelize

/** Internal same-version wire result. */
@Parcelize
internal class PrivilegeInputStatusResult(
    override var error: PrivilegeInputError? = null,
) : PrivilegeInputResult
