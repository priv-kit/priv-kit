package priv.kit.evdev.internal

import kotlinx.parcelize.Parcelize

/** Internal same-version wire result. */
@Parcelize
internal class PrivilegeInputIntArrayResult(
    override var error: PrivilegeInputError? = null,
    var values: IntArray = intArrayOf(),
) : PrivilegeInputResult
