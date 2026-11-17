package priv.kit.external.shizuku

import android.content.Context
import kotlinx.coroutines.flow.Flow
import priv.kit.core.PrivilegeExternalStartProvider
import priv.kit.core.PrivilegeExternalStartResult
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.external.shizuku.internal.ShizukuClient
import priv.kit.external.shizuku.internal.ShizukuStarter

/** Authorization and startup through Shizuku on API v13 or later. */
public object PrivilegeExternalShizuku : PrivilegeExternalStartProvider {
    override val id: String = "shizuku"
    override fun getLabel(context: Context): CharSequence = "Shizuku"

    /** Emits whether a supported session is attached. */
    public val connected: Flow<Boolean> get() = ShizukuClient.connected

    /** Checks the current session and authorization without opening a prompt. */
    public suspend fun status(): PrivilegeExternalShizukuStatus = ShizukuClient.status()

    /** Returns whether authorization was granted, or null if no supported session can answer. */
    public suspend fun requestAuthorization(): Boolean? =
        ShizukuClient.requestAuthorization()

    override suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult =
        status().toStartResult(context)

    override suspend fun requestAuthorization(context: Context): PrivilegeExternalStartResult {
        val granted = requestAuthorization()
        return status().toStartResult(context, denied = granted == false)
    }

    override suspend fun runStarter(
        context: Context,
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String = ShizukuStarter.run(context, commandLine, startupLogListener)

    private fun PrivilegeExternalShizukuStatus.toStartResult(
        context: Context,
        denied: Boolean = false,
    ): PrivilegeExternalStartResult = when (this) {
        PrivilegeExternalShizukuStatus.UNAVAILABLE -> PrivilegeExternalStartResult(
            authorized = null,
            message = context.getText(R.string.priv_external_shizuku_unavailable),
        )
        PrivilegeExternalShizukuStatus.UNSUPPORTED -> PrivilegeExternalStartResult(
            authorized = null,
            message = context.getText(R.string.priv_external_shizuku_unsupported),
        )
        PrivilegeExternalShizukuStatus.UNAUTHORIZED -> PrivilegeExternalStartResult(
            authorized = false,
            message = context.getText(
                if (denied) R.string.priv_external_shizuku_denied else R.string.priv_external_shizuku_permission_required,
            ),
        )
        PrivilegeExternalShizukuStatus.AUTHORIZED -> PrivilegeExternalStartResult(
            authorized = true,
            message = context.getText(R.string.priv_external_shizuku_authorized),
        )
    }
}

public enum class PrivilegeExternalShizukuStatus {
    UNAVAILABLE,
    UNSUPPORTED,
    UNAUTHORIZED,
    AUTHORIZED,
}
