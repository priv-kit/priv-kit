package priv.kit.sample.startup

import android.app.Application
import android.content.Context
import priv.kit.external.shizuku.PrivilegeExternalShizuku
import priv.kit.ui.PrivilegeAdbPairingService
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiStartupMode
import priv.kit.ui.PrivilegeUiViewModel

internal class PrivilegeSamplePrivilegeUiViewModel(
    application: Application,
    private val host: PrivilegeSamplePrivilegeUiCallbacks,
) : PrivilegeUiViewModel(application, host.config) {
    override fun onBackClick(): Boolean {
        host.back()
        return true
    }

}

internal val privilegeUiConfig by lazy {
    PrivilegeUiConfig(
        startupModes = listOf(
            PrivilegeUiStartupMode.ROOT,
            PrivilegeUiStartupMode.ADB,
            PrivilegeUiStartupMode.MANUAL_SHELL,
        ),
        externalStartProviders = listOf(PrivilegeExternalShizuku),
        manualShellScriptPath = "priv.sh",
    )
}

internal fun startPrivilegeSampleNotificationPairing(
    context: Context,
    ownerId: String,
    statusText: String,
): Boolean =
    PrivilegeAdbPairingService.start(
        context = context,
        ownerId = ownerId,
        statusText = statusText,
    )

internal fun stopPrivilegeSampleNotificationPairing(context: Context, ownerId: String) {
    PrivilegeAdbPairingService.stop(context, ownerId)
}
