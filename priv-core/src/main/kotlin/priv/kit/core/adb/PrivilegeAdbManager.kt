package priv.kit.core.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import androidx.annotation.RequiresApi
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.internal.core.PrivilegeServerLaunchCommand
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.shared.PRIVILEGE_INTERNAL_DEFAULT_ADB_AUTHORIZATION_TIMEOUT_MILLIS

public class PrivilegeAdbManager private constructor(
    identity: PrivilegeAdbIdentity,
    loadKeyBytes: () -> ByteArray,
    nsdManagerProvider: () -> NsdManager,
    wirelessDebuggingControllerProvider: () -> PrivilegeAdbWirelessDebuggingController,
) {
    private val identityProvider = PrivilegeAdbIdentityProvider(
        identity = identity,
        loadKeyBytes = loadKeyBytes,
    )
    private val endpointResolver = PrivilegeAdbEndpointResolver(
        nsdManagerProvider = nsdManagerProvider,
        wirelessDebuggingControllerProvider = wirelessDebuggingControllerProvider,
    )
    private val startupCoordinator = PrivilegeAdbStartupCoordinator(
        identityProvider = identityProvider,
        endpointResolver = endpointResolver,
    )
    private val pairingManager = PrivilegeAdbPairingManager(
        identityProvider = identityProvider,
        endpointResolver = endpointResolver,
    )
    private val tcpManager = PrivilegeAdbTcpManager(
        identityProvider = identityProvider,
        endpointResolver = endpointResolver,
        wirelessDebuggingControllerProvider = wirelessDebuggingControllerProvider,
    )

    @Throws(PrivilegeStartupException::class)
    internal suspend fun start(
        command: PrivilegeServerLaunchCommand,
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeAdbStartResult =
        startupCoordinator.start(
            command = command,
            options = options,
            startupLogListener = startupLogListener,
        )

    @Throws(PrivilegeStartupException::class)
    public fun getIdentityInfo(): PrivilegeAdbIdentityInfo =
        identityProvider.getIdentityInfo()

    public fun getActiveTcpPort(): Int? =
        tcpManager.getActiveTcpPort()

    public fun getConfiguredTcpPort(): Int? =
        tcpManager.getConfiguredTcpPort()

    public suspend fun prepareTcpForStart(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbAuthorizationCheckResult =
        tcpManager.prepareTcpForStart(tcpPort)

    @RequiresApi(Build.VERSION_CODES.R)
    public fun getWirelessDebuggingControlStatus(): PrivilegeAdbWirelessDebuggingControlStatus =
        tcpManager.getWirelessDebuggingControlStatus()

    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(PrivilegeStartupException::class)
    public suspend fun checkPairing(
        port: Int? = null,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbPairingCheckResult =
        pairingManager.checkPairing(
            port = port,
            discoverPort = discoverPort,
            portDiscoveryTimeoutMillis = portDiscoveryTimeoutMillis,
        )

    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(PrivilegeStartupException::class)
    public fun openPairingCheckSession(
        port: Int? = null,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbPairingCheckSession =
        pairingManager.openPairingCheckSession(
            port = port,
            discoverPort = discoverPort,
            portDiscoveryTimeoutMillis = portDiscoveryTimeoutMillis,
        )

    internal suspend fun readRuntimeDiagnostics(
        endpoint: PrivilegeAdbEndpoint,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String =
        startupCoordinator.readRuntimeDiagnostics(
            endpoint = endpoint,
            startupLogListener = startupLogListener,
        )

    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(PrivilegeStartupException::class)
    public suspend fun pair(
        pairingCode: String,
        port: Int? = null,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbPairingResult =
        pairingManager.pair(
            pairingCode = pairingCode,
            port = port,
            discoverPort = discoverPort,
            portDiscoveryTimeoutMillis = portDiscoveryTimeoutMillis,
        )

    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(PrivilegeStartupException::class)
    public suspend fun discoverPairingPort(
        timeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): Int =
        pairingManager.discoverPairingPort(timeoutMillis)

    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(PrivilegeStartupException::class)
    public suspend fun discoverConnectPort(
        timeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): Int =
        tcpManager.discoverConnectPort(timeoutMillis)

    @Throws(PrivilegeStartupException::class)
    public suspend fun switchToTcp(
        currentPort: Int? = null,
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
        options: PrivilegeAdbStartOptions? = null,
    ): PrivilegeAdbTcpResult =
        tcpManager.switchToTcp(
            currentPort = currentPort,
            tcpPort = tcpPort,
            options = options,
        )

    @Throws(PrivilegeStartupException::class)
    public suspend fun stopTcp(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbTcpResult =
        tcpManager.stopTcp(tcpPort)

    public suspend fun checkTcpAuthorization(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbAuthorizationCheckResult =
        tcpManager.checkTcpAuthorization(tcpPort)

    @Throws(PrivilegeStartupException::class)
    public fun openTcpAuthorizationCheckSession(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbTcpAuthorizationCheckSession =
        tcpManager.openTcpAuthorizationCheckSession(tcpPort)

    public suspend fun requestTcpAuthorization(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
        timeoutMillis: Long = PRIVILEGE_INTERNAL_DEFAULT_ADB_AUTHORIZATION_TIMEOUT_MILLIS,
    ): PrivilegeAdbAuthorizationRequestResult =
        tcpManager.requestTcpAuthorization(
            tcpPort = tcpPort,
            timeoutMillis = timeoutMillis,
        )

    internal companion object {
        internal fun create(
            adbDeviceName: String = PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME,
        ): PrivilegeAdbManager =
            PrivilegeContext.require().let { applicationContext ->
                PrivilegeAdbManager(
                    identity = PrivilegeAdbIdentity.default(deviceName = adbDeviceName),
                    loadKeyBytes = { PrivilegeAdbKeyStore.readOrCreate() },
                    nsdManagerProvider = { requireNsdManager(applicationContext) },
                    wirelessDebuggingControllerProvider = {
                        AndroidPrivilegeAdbWirelessDebuggingController(applicationContext)
                    },
                )
            }

        private fun requireNsdManager(context: Context): NsdManager =
            context.getSystemService(NsdManager::class.java)
                ?: throw PrivilegeStartupException("NSD manager is unavailable")
    }
}
