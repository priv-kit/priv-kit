package priv.kit.evdev.internal

import kotlinx.parcelize.Parcelize

/** Internal same-version wire result. */
@Parcelize
internal class PrivilegeInputOpenResult(
    override var error: PrivilegeInputError? = null,
    var handle: Int = 0,
) : PrivilegeInputResult
