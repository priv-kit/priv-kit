@file:Suppress("CAST_NEVER_SUCCEEDS")

package priv.kit.shared

import android.content.Context
import android.content.ContextHidden
import android.os.Parcel
import android.os.ParcelHidden

public val Context.toHidden: ContextHidden
    get() = this as ContextHidden

public val Parcel.toHidden: ParcelHidden
    get() = this as ParcelHidden
