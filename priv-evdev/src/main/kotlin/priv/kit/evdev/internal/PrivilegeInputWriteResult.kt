package priv.kit.evdev.internal

import kotlinx.parcelize.Parcelize

/** Internal same-version wire result. */
@Parcelize
internal class PrivilegeInputWriteResult(
    override var error: PrivilegeInputError? = null,
    var submittedEventCount: Int = -1,
    var errno: Int = 0,
) : PrivilegeInputResult
