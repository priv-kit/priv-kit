package priv.kit.sample.startup

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.sample.R
import priv.kit.sample.common.toDiagnosticString
import priv.kit.ui.PrivilegeAdbPairingService
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiExternalStartSnapshot
import priv.kit.ui.PrivilegeUiStreamingExternalStartProvider
import priv.kit.ui.PrivilegeUiStartupMode
import priv.kit.ui.PrivilegeUiViewModel
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal class PrivilegeSamplePrivilegeUiViewModel(
    application: Application,
    private val host: PrivilegeSamplePrivilegeUiCallbacks,
) : PrivilegeUiViewModel(application, host.config) {
    override fun onBackClick(): Boolean {
        host.back()
        return true
    }

    override fun onConnected(serverInfo: PrivilegeServerInfo) {
        host.connected(serverInfo)
    }
}

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

private class PrivilegeSampleShizukuExternalStartProvider(
    override val label: CharSequence,
) : PrivilegeUiStreamingExternalStartProvider {
    override val id: String = "shizuku"

    override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
        runCatching {
            shizukuSnapshot(context)
        }.getOrElse { throwable ->
            PrivilegeUiExternalStartSnapshot(
                message = throwable.message ?: throwable.javaClass.name,
                exceptionText = throwable.toDiagnosticString(),
            )
        }

    override suspend fun requestAuthorization(
        context: Context,
    ): PrivilegeUiExternalStartSnapshot {
        val current = snapshot(context)
        if (
            !current.available ||
            current.canStart ||
            Shizuku.shouldShowRequestPermissionRationale()
        ) {
            return current
        }

        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            fun finish(result: PrivilegeUiExternalStartSnapshot) {
                if (!completed.compareAndSet(false, true)) return
                Shizuku.removeRequestPermissionResultListener(listener)
                if (continuation.isActive) continuation.resume(result)
            }

            listener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                    finish(shizukuSnapshot(context))
                }
            }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    finish(shizukuSnapshot(context))
                } else {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                }
            } catch (throwable: Throwable) {
                finish(
                    PrivilegeUiExternalStartSnapshot(
                        message = throwable.message ?: throwable.javaClass.name,
                        exceptionText = throwable.toDiagnosticString(),
                    ),
                )
            }
        }
    }

    override suspend fun start(
        context: Context,
        commandLine: String,
    ) {
        PrivilegeSampleShizukuExternalStarter(context).use { starter ->
            starter.start(commandLine)
        }
    }

    override suspend fun start(
        context: Context,
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener,
    ) {
        PrivilegeSampleShizukuExternalStarter(context).use { starter ->
            starter.start(commandLine, startupLogListener)
        }
    }

    private fun shizukuSnapshot(context: Context): PrivilegeUiExternalStartSnapshot {
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
        if (version < PrivilegeSampleShizukuExternalStarter.SHIZUKU_USER_SERVICE_MIN_VERSION) {
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

        return PrivilegeUiExternalStartSnapshot(
            available = true,
            uid = uid,
            version = version,
            message = context.getString(R.string.sample_privilege_ui_shizuku_permission_required),
        )
    }
}
