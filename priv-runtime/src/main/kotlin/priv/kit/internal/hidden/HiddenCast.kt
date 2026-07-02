@file:Suppress("CAST_NEVER_SUCCEEDS")

package priv.kit.internal.hidden

import android.content.Context
import android.content.ContextHidden

internal inline val Context.castedHidden: ContextHidden
    get() = this as ContextHidden
