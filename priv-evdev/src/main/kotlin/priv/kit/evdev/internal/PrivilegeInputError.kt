package priv.kit.evdev.internal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Shared operation error. Null error on a result means success; transport failures are separate. */
@Parcelize
internal class PrivilegeInputError(
    val code: Int,
    val errno: Int = 0,
    val message: String? = null,
    val displayId: Int = -1,
    val requestedEventCount: Int = 0,
    val submittedEventCount: Int? = null,
) : Parcelable {
    companion object {
        const val DEVICE = 1
        const val IO = 2
        const val ARGUMENT = 3
        const val STATE = 4
        const val TARGET = 5
        const val WRITE = 6
    }
}

internal interface PrivilegeInputResult : Parcelable {
    var error: PrivilegeInputError?
}
