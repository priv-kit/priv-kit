package priv.kit.sample

import android.content.Context
import android.content.pm.PackageManager
import priv.kit.core.PrivilegeServerInfo
import priv.kit.runtime.PrivilegeExternalStartCommand
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiExternalStartProvider
import priv.kit.ui.PrivilegeUiExternalStartSnapshot
import priv.kit.ui.PrivilegeUiStartupMode
import rikka.shizuku.Shizuku

internal const val SAMPLE_SHIZUKU_EXTERNAL_START_ID = "shizuku"

internal fun createPrivilegeSampleUiConfig(context: Context): PrivilegeUiConfig =
    PrivilegeUiConfig(
        startupModes = setOf(
            PrivilegeUiStartupMode.ADB,
            PrivilegeUiStartupMode.MANUAL_SHELL,
            PrivilegeUiStartupMode.ROOT,
        ),
        externalStartProviders = listOf(
            PrivilegeSampleShizukuExternalStartProvider(
                label = context.getString(R.string.sample_privilege_ui_shizuku_label),
            ),
        ),
    )

internal fun MainActivity.handlePrivilegeUiConnected(serverInfo: PrivilegeServerInfo) {
    screenState = screenState.copy(
        busy = false,
        status = PrivilegeSampleStatus.CONNECTED,
        serverInfo = serverInfo,
        message = "Connected",
    )
}

private class PrivilegeSampleShizukuExternalStartProvider(
    override val label: CharSequence,
) : PrivilegeUiExternalStartProvider {
    override val id: String = SAMPLE_SHIZUKU_EXTERNAL_START_ID

    override fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
        runCatching {
            shizukuSnapshot(context, requestPermission = false)
        }.getOrElse { throwable ->
            PrivilegeUiExternalStartSnapshot(
                message = throwable.message ?: throwable.javaClass.name,
                exceptionText = throwable.toDiagnosticString(),
            )
        }

    override fun requestAuthorization(context: Context): PrivilegeUiExternalStartSnapshot =
        runCatching {
            shizukuSnapshot(context, requestPermission = true)
        }.getOrElse { throwable ->
            PrivilegeUiExternalStartSnapshot(
                message = throwable.message ?: throwable.javaClass.name,
                exceptionText = throwable.toDiagnosticString(),
            )
        }

    override fun start(
        context: Context,
        command: PrivilegeExternalStartCommand,
    ) {
        PrivilegeSampleShizukuExternalStarter(context).use { starter ->
            starter.start(command)
        }
    }

    private fun shizukuSnapshot(
        context: Context,
        requestPermission: Boolean,
    ): PrivilegeUiExternalStartSnapshot {
        if (!Shizuku.pingBinder()) {
            return PrivilegeUiExternalStartSnapshot(
                message = context.getString(R.string.sample_privilege_ui_shizuku_not_running),
            )
        }
        if (Shizuku.isPreV11()) {
            return PrivilegeUiExternalStartSnapshot(
                message = context.getString(R.string.sample_privilege_ui_shizuku_pre_v11),
            )
        }

        val version = Shizuku.getVersion()
        val uid = Shizuku.getUid().takeIf { it >= 0 }
        if (version < SHIZUKU_USER_SERVICE_MIN_VERSION) {
            return PrivilegeUiExternalStartSnapshot(
                available = false,
                uid = uid,
                version = version,
                message = context.getString(R.string.sample_privilege_ui_shizuku_version_unsupported),
            )
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return PrivilegeUiExternalStartSnapshot(
                available = true,
                authorized = true,
                uid = uid,
                version = version,
                message = context.getString(R.string.sample_privilege_ui_shizuku_ready),
            )
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return PrivilegeUiExternalStartSnapshot(
                available = true,
                uid = uid,
                version = version,
                message = context.getString(R.string.sample_privilege_ui_shizuku_permission_denied),
            )
        }

        if (requestPermission) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            return PrivilegeUiExternalStartSnapshot(
                available = true,
                uid = uid,
                version = version,
                message = context.getString(R.string.sample_privilege_ui_shizuku_permission_requested),
            )
        }

        return PrivilegeUiExternalStartSnapshot(
            available = true,
            uid = uid,
            version = version,
            message = context.getString(R.string.sample_privilege_ui_shizuku_permission_required),
        )
    }
}
