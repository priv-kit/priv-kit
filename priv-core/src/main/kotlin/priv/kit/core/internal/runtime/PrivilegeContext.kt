package priv.kit.core.internal.runtime

import android.content.Context
import androidx.annotation.RestrictTo
import priv.kit.core.PrivilegeStartupException

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public object PrivilegeContext {
    @Volatile
    private var applicationContext: Context? = null

    public fun install(context: Context) {
        applicationContext = context.applicationContext
    }

    @Throws(PrivilegeStartupException::class)
    public fun require(): Context =
        applicationContext ?: throw PrivilegeStartupException(
            "Privilege runtime context is not initialized; ensure PrivilegeHandshakeProvider is registered",
        )
}
