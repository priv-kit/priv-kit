package priv.kit.evdev.internal

import kotlinx.parcelize.Parcelize

/** Internal same-version wire result. */
@Parcelize
internal class PrivilegeInputAxisResult(
    override var error: PrivilegeInputError? = null,
    var value: Int = 0,
    var minimum: Int = 0,
    var maximum: Int = 0,
    var fuzz: Int = 0,
    var flat: Int = 0,
    var resolution: Int = 0,
) : PrivilegeInputResult
