package priv.kit.sample

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import priv.kit.Privilege
import priv.kit.PrivilegeUserServiceConnection
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var sampleViewModel: PrivilegeSampleViewModel
    internal val executor = Executors.newSingleThreadExecutor()
    internal var serverConnectedListener: Closeable? = null
    internal var serverDisconnectedWatcher: Closeable? = null
    internal var sampleMqsNativeBinder: IBinder? = null
    internal var sampleUserManager: PrivilegeSampleUserManagerProxy? = null
    internal var dedicatedUserServiceConnection: PrivilegeUserServiceConnection? = null
    internal var embeddedUserServiceConnection: PrivilegeUserServiceConnection? = null
    internal var dedicatedUserService: IPrivilegeSampleDedicatedUserService? = null
    internal var embeddedUserService: IPrivilegeSampleEmbeddedUserService? = null
    @Volatile
    internal var shizukuExternalStarter: PrivilegeSampleShizukuExternalStarter? = null
    private var notificationPermissionResultHandler: ((Boolean) -> Unit)? = null
    internal var startNotificationPairingAfterPermission = false
    internal var startShizukuExternalAfterPermission = false
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuStatus(append = false)
    }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        handleShizukuBinderDead()
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            handleNotificationPermissionResult(granted)
    }
    internal val manualShellCommandLine: String by lazy(LazyThreadSafetyMode.NONE) {
        Privilege.createShellStartCommand().toSampleHostAdbShellCommand()
    }
    internal var screenState: PrivilegeSampleScreenState
        get() = sampleViewModel.screenState
        set(value) {
            sampleViewModel.screenState = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sampleViewModel = ViewModelProvider(this)[PrivilegeSampleViewModel::class.java]
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        initializePrivilegeSample()
        setContent {
            PrivilegeSampleTheme {
                PrivilegeSampleScreen(
                    state = screenState,
                    backStack = sampleViewModel.backStack,
                    selectedStartupTab = sampleViewModel.selectedStartupTab,
                    onDestinationSelected = { sampleViewModel.selectDestination(it) },
                    onStartupTabSelected = { sampleViewModel.selectStartupTab(it) },
                    onOpenPrivilegeUi = { sampleViewModel.openPrivilegeUi() },
                    onPrivilegeUiBack = { sampleViewModel.navigateBack() },
                    onPrivilegeUiHelp = {},
                    onPrivilegeUiConnected = { handlePrivilegeUiConnected(it) },
                    onPrivilegeUiNotificationPermissionRequired = { handler ->
                        requestPrivilegeUiNotificationPermission(handler)
                    },
                    onPrivilegeUiNotificationPermissionDisposed = { handler ->
                        clearPrivilegeUiNotificationPermissionHandler(handler)
                    },
                    onAdbDeviceNameChanged = { updateAdbDeviceName(it) },
                    onRefreshAdbFingerprint = { refreshAdbFingerprint() },
                    onCheckAdbPairing = { checkWirelessAdbPairing(showBusy = true) },
                    onPairingCodeChanged = { updatePairingCode(it) },
                    onTcpPortChanged = { updateTcpPort(it) },
                    onStartRootRuntime = { startRootRuntime() },
                    onCopyManualCommand = { copyManualShellCommand() },
                    onStartShizukuExternal = { startShizukuExternal() },
                    onPairWirelessAdb = { pairWirelessAdb() },
                    onStartNotificationPairing = { startNotificationPairing() },
                    onStopNotificationPairing = { stopNotificationPairing() },
                    onStartWirelessAdb = { startWirelessAdb() },
                    onSwitchToTcp = { switchToTcp() },
                    onRestartTcp = { restartTcp() },
                    onStopTcp = { stopTcp() },
                    onStopServer = { stopServer() },
                    onGetUserManager = { getUserManagerBinder() },
                    onGetUsers = { getUserManagerUsers() },
                    onRunImqsNative = { runImqsNative() },
                    onBindDedicatedUserService = { bindDedicatedUserService() },
                    onCallDedicatedUserService = { callDedicatedUserService() },
                    onStopDedicatedUserService = { stopDedicatedUserService() },
                    onBindEmbeddedUserService = { bindEmbeddedUserService() },
                    onCallEmbeddedUserService = { callEmbeddedUserService() },
                    onStopEmbeddedUserService = { stopEmbeddedUserService() },
                    onClearLog = { clearLog() },
                    onCopyLog = { copySessionLog() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handleShizukuHostVisible()
    }

    private fun handleNotificationPermissionResult(granted: Boolean) {
        val pendingPrivilegeUiHandler = notificationPermissionResultHandler
        notificationPermissionResultHandler = null
        pendingPrivilegeUiHandler?.invoke(granted)

        val shouldStartSamplePairing = startNotificationPairingAfterPermission
        startNotificationPairingAfterPermission = false
        if (granted && shouldStartSamplePairing) {
            startNotificationPairing()
        } else if (!granted && shouldStartSamplePairing) {
            screenState = screenState.copy(
                notificationPairingRunning = false,
                pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
                pairingMessage = "Notification permission is required to enter the pairing code from a notification.",
                message = "Notification permission not granted",
            )
        }
    }

    internal fun requestNotificationPermission(onResult: ((Boolean) -> Unit)? = null) {
        notificationPermissionResultHandler = onResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            handleNotificationPermissionResult(granted = true)
        }
    }

    override fun onDestroy() {
        releasePrivilegeSample()
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        super.onDestroy()
    }

    private fun requestPrivilegeUiNotificationPermission(onResult: (Boolean) -> Unit) {
        requestNotificationPermission(onResult)
    }

    private fun clearPrivilegeUiNotificationPermissionHandler(handler: (Boolean) -> Unit) {
        if (notificationPermissionResultHandler === handler) {
            notificationPermissionResultHandler = null
        }
    }
}

private fun String.toSampleHostAdbShellCommand(): String {
    val command = trim()
    return if (command.startsWith(ADB_SHELL_PREFIX)) {
        command
    } else {
        ADB_SHELL_PREFIX + command
    }
}

private const val ADB_SHELL_PREFIX = "adb shell "
