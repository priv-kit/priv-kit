package priv.kit.evdev

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Maps current-display pixels to the device's natural axis range.
 * rotation uses Surface.ROTATION_* (0..3). After undoing rotation, swapAxes and
 * invertX/invertY correct the device axis orientation. Does not route to a display.
 */
@Parcelize
public data class PrivilegeEvdevTouchMapping(
    public val width: Int,
    public val height: Int,
    public val rotation: Int = 0,
    public val swapAxes: Boolean = false,
    public val invertX: Boolean = false,
    public val invertY: Boolean = false,
) : Parcelable {
    init {
        require(width >= 2 && height >= 2) { "Display dimensions must be at least 2 pixels" }
        require(rotation in 0..3) { "Expected Surface.ROTATION_*" }
    }
}
