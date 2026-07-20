package priv.kit.ui.runtime

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.adb.PrivilegeAdbStartOptions
import priv.kit.core.adb.PrivilegeAdbWirelessDebuggingControl
import priv.kit.core.internal.runtime.PrivilegeRuntimeClientLaunch
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiExternalStartProvider
import priv.kit.ui.PrivilegeUiStartupMode
import priv.kit.ui.effectiveStartupModes
import priv.kit.ui.adb.privilegeUiRequiredLocalNetworkPermission
import priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported
import priv.kit.ui.state.toPrivilegeUiAdbDeviceNameText
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeUiSilentStartRunner(
    context: Context,
    private val config: PrivilegeUiConfig,
    private val backend: PrivilegeUiSilentStartBackend = PrivilegeUiPlatformSilentStartBackend,
    private val requiredLocalNetworkPermission: (Context) -> String? =
        ::privilegeUiRequiredLocalNetworkPermission,
) {
    private val applicationContext = context.applicationContext

    suspend fun start(
        method: PrivilegeUiStartMethod,
        launch: PrivilegeRuntimeClientLaunch,
    ): PrivilegeServerInfo? =
        when (method) {
            PrivilegeUiStartMethod.Root -> startRoot(launch)
            PrivilegeUiStartMethod.AdbWireless -> startWirelessAdb(launch)
            PrivilegeUiStartMethod.AdbTcpip -> startTcpipAdb(launch)
            is PrivilegeUiStartMethod.External -> startExternal(method.providerId, launch)
        }

    private suspend fun startRoot(launch: PrivilegeRuntimeClientLaunch): PrivilegeServerInfo? {
        if (PrivilegeUiStartupMode.ROOT !in config.effectiveStartupModes()) return null
        return runInterruptible { backend.startRoot(launch, config.startTimeoutMillis) }
    }

    private suspend fun startWirelessAdb(
        launch: PrivilegeRuntimeClientLaunch,
    ): PrivilegeServerInfo? {
        if (
            PrivilegeUiStartupMode.ADB !in config.effectiveStartupModes() ||
            !isPrivilegeUiWirelessAdbSupported() ||
            requiredLocalNetworkPermission(applicationContext) != null
        ) {
            return null
        }
        return runInterruptible {
            backend.startAdb(
                launch = launch,
                options = PrivilegeAdbStartOptions(
                    discoverPort = true,
                    tcpMode = false,
                    tcpPort = config.tcpPort,
                    wirelessDebuggingControl = if (config.enableManagedWirelessAdb) {
                        PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE
                    } else {
                        PrivilegeAdbWirelessDebuggingControl.NEVER
                    },
                ),
                timeoutMillis = config.startTimeoutMillis,
                adbDeviceName = config.adbDeviceName.toSilentAdbDeviceName(),
            )
        }
    }

    private suspend fun startTcpipAdb(
        launch: PrivilegeRuntimeClientLaunch,
    ): PrivilegeServerInfo? {
        if (
            PrivilegeUiStartupMode.ADB !in config.effectiveStartupModes() ||
            config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED
        ) {
            return null
        }
        return runInterruptible {
            backend.startAdb(
                launch = launch,
                options = PrivilegeAdbStartOptions(
                    port = config.tcpPort,
                    discoverPort = false,
                    tcpMode = false,
                    tcpPort = config.tcpPort,
                    wirelessDebuggingControl = PrivilegeAdbWirelessDebuggingControl.NEVER,
                ),
                timeoutMillis = config.startTimeoutMillis,
                adbDeviceName = config.adbDeviceName.toSilentAdbDeviceName(),
            )
        }
    }

    private suspend fun startExternal(
        providerId: String,
        launch: PrivilegeRuntimeClientLaunch,
    ): PrivilegeServerInfo? =
        withTimeoutOrNull(config.startTimeoutMillis.milliseconds) {
            val provider = config.externalStartProviders.firstOrNull { it.id == providerId }
                ?: return@withTimeoutOrNull null
            val snapshot = runInterruptible { provider.snapshot(applicationContext) }
            if (!snapshot.canStart) return@withTimeoutOrNull null
            backend.startExternal(
                launch = launch,
                context = applicationContext,
                provider = provider,
                timeoutMillis = config.startTimeoutMillis,
            )
        }
}

internal interface PrivilegeUiSilentStartBackend {
    fun startRoot(
        launch: PrivilegeRuntimeClientLaunch,
        timeoutMillis: Long,
    ): PrivilegeServerInfo

    fun startAdb(
        launch: PrivilegeRuntimeClientLaunch,
        options: PrivilegeAdbStartOptions,
        timeoutMillis: Long,
        adbDeviceName: String?,
    ): PrivilegeServerInfo

    suspend fun startExternal(
        launch: PrivilegeRuntimeClientLaunch,
        context: Context,
        provider: PrivilegeUiExternalStartProvider,
        timeoutMillis: Long,
    ): PrivilegeServerInfo?
}

private object PrivilegeUiPlatformSilentStartBackend : PrivilegeUiSilentStartBackend {
    override fun startRoot(
        launch: PrivilegeRuntimeClientLaunch,
        timeoutMillis: Long,
    ): PrivilegeServerInfo =
        PrivilegeRuntimeStartCoordinator.startRoot(
            launch = launch,
            timeoutMillis = timeoutMillis,
        )

    override fun startAdb(
        launch: PrivilegeRuntimeClientLaunch,
        options: PrivilegeAdbStartOptions,
        timeoutMillis: Long,
        adbDeviceName: String?,
    ): PrivilegeServerInfo =
        PrivilegeRuntimeStartCoordinator.startAdb(
            launch = launch,
            options = options,
            timeoutMillis = timeoutMillis,
            adbDeviceName = adbDeviceName,
        )

    override suspend fun startExternal(
        launch: PrivilegeRuntimeClientLaunch,
        context: Context,
        provider: PrivilegeUiExternalStartProvider,
        timeoutMillis: Long,
    ): PrivilegeServerInfo? {
        return withTimeoutOrNull(timeoutMillis.milliseconds) {
            val commandLine = PrivilegeRuntimeStartCoordinator.createShellStartCommand(launch)
            val connected = CompletableDeferred<PrivilegeServerInfo>()
            val listener = Privilege.addServerConnectedListener { serverInfo ->
                connected.complete(serverInfo)
            }
            listener.use {
                if (connected.isCompleted) return@withTimeoutOrNull connected.await()
                runInterruptible { provider.start(context, commandLine) }
                connected.await()
            }
        }
    }
}

private fun String?.toSilentAdbDeviceName(): String? =
    this
        ?.toPrivilegeUiAdbDeviceNameText()
        ?.ifBlank { null }
