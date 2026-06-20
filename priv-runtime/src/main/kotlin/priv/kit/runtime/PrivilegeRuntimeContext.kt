package priv.kit.runtime

import android.content.Context
import priv.kit.core.PrivilegeStartupException

internal object PrivilegeRuntimeContext {
    @Volatile
    private var applicationContext: Context? = null

    fun install(context: Context) {
        applicationContext = context.applicationContext
    }

    @Throws(PrivilegeStartupException::class)
    fun require(): Context =
        applicationContext ?: throw PrivilegeStartupException(
            "Privilege runtime context is not initialized; ensure PrivilegeHandshakeProvider is registered",
        )
}
