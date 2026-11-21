package priv.kit.sample.startup

import android.app.Application
import android.content.Context
import priv.kit.external.shizuku.PrivilegeExternalShizuku
import priv.kit.ui.PrivilegeAdbPairingService
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiStartupMode
import priv.kit.ui.m3.PrivilegeM3ViewModel

internal class PrivilegeSamplePrivilegeUiViewModel(
    application: Application,
    config: PrivilegeUiConfig,
) : PrivilegeM3ViewModel(application, config)

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
