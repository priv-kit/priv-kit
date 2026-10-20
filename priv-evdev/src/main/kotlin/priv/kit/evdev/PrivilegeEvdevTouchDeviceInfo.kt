package priv.kit.evdev

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** A snapshot of a touch node's system association. Null IDs mean unknown, not display 0.
 * IDs and paths can change after reconnects; query again before selecting a device.
 */
@Parcelize
public data class PrivilegeEvdevTouchDeviceInfo(
    public val path: String,
    public val name: String,
    public val inputDeviceId: Int?,
    public val displayId: Int?,
) : Parcelable
