package priv.kit.sample

import android.content.Context
import android.content.pm.PackageManager
import priv.kit.core.PrivilegeServerInfo
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiDelegateProvider
import priv.kit.ui.PrivilegeUiDelegateSnapshot
import priv.kit.ui.PrivilegeUiStartupMode
import rikka.shizuku.Shizuku

internal const val SAMPLE_SHIZUKU_DELEGATE_ID = "shizuku"

internal fun createPrivilegeSampleUiConfig(context: Context): PrivilegeUiConfig =
    PrivilegeUiConfig(
        startupModes = setOf(
            PrivilegeUiStartupMode.ADB,
            PrivilegeUiStartupMode.MANUAL_SHELL,
            PrivilegeUiStartupMode.ROOT,
        ),
        delegateProviders = listOf(
            PrivilegeSampleShizukuDelegateProvider(
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

private class PrivilegeSampleShizukuDelegateProvider(
    override val label: CharSequence,
) : PrivilegeUiDelegateProvider {
    override val id: String = SAMPLE_SHIZUKU_DELEGATE_ID

    override fun snapshot(context: Context): PrivilegeUiDelegateSnapshot =
        runCatching {
            shizukuSnapshot(context, requestPermission = false)
        }.getOrElse { throwable ->
            PrivilegeUiDelegateSnapshot(
                message = throwable.message ?: throwable.javaClass.name,
                exceptionText = throwable.toDiagnosticString(),
            )
        }

    override fun requestAuthorization(context: Context): PrivilegeUiDelegateSnapshot =
        runCatching {
            shizukuSnapshot(context, requestPermission = true)
        }.getOrElse { throwable ->
            PrivilegeUiDelegateSnapshot(
                message = throwable.message ?: throwable.javaClass.name,
                exceptionText = throwable.toDiagnosticString(),
            )
        }

    override fun createExecutor(context: Context): PrivilegeSampleShizukuDelegateExecutor =
        PrivilegeSampleShizukuDelegateExecutor(context)

    private fun shizukuSnapshot(
        context: Context,
        requestPermission: Boolean,
    ): PrivilegeUiDelegateSnapshot {
        if (!Shizuku.pingBinder()) {
            return PrivilegeUiDelegateSnapshot(
                message = context.getString(R.string.sample_privilege_ui_shizuku_not_running),
            )
        }
        if (Shizuku.isPreV11()) {
            return PrivilegeUiDelegateSnapshot(
                message = context.getString(R.string.sample_privilege_ui_shizuku_pre_v11),
            )
        }

        val version = Shizuku.getVersion()
        val uid = Shizuku.getUid().takeIf { it >= 0 }
        if (version < SHIZUKU_USER_SERVICE_MIN_VERSION) {
            return PrivilegeUiDelegateSnapshot(
                available = false,
                uid = uid,
                version = version,
                message = context.getString(R.string.sample_privilege_ui_shizuku_version_unsupported),
            )
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return PrivilegeUiDelegateSnapshot(
                available = true,
                authorized = true,
                uid = uid,
                version = version,
                message = context.getString(R.string.sample_privilege_ui_shizuku_ready),
            )
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return PrivilegeUiDelegateSnapshot(
                available = true,
                uid = uid,
                version = version,
                message = context.getString(R.string.sample_privilege_ui_shizuku_permission_denied),
            )
        }

        if (requestPermission) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            return PrivilegeUiDelegateSnapshot(
                available = true,
                uid = uid,
                version = version,
                message = context.getString(R.string.sample_privilege_ui_shizuku_permission_requested),
            )
        }

        return PrivilegeUiDelegateSnapshot(
            available = true,
            uid = uid,
            version = version,
            message = context.getString(R.string.sample_privilege_ui_shizuku_permission_required),
        )
    }
}
