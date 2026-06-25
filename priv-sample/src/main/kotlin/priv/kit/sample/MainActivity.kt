package priv.kit.sample

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import priv.kit.runtime.PrivilegeRuntime
import priv.kit.runtime.PrivilegeUserServiceConnection
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiViewModel
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var sampleViewModel: PrivilegeSampleViewModel
    private lateinit var privilegeUiViewModel: PrivilegeUiViewModel
    private lateinit var privilegeUiConfig: PrivilegeUiConfig
    internal val executor = Executors.newSingleThreadExecutor()
    internal var readyServerWatcher: Closeable? = null
    internal var serverDisconnectedWatcher: Closeable? = null
    internal var dedicatedUserServiceStatusWatcher: Closeable? = null
    internal var embeddedUserServiceStatusWatcher: Closeable? = null
    internal var sampleMqsNativeBinder: IBinder? = null
    internal var sampleUserManager: PrivilegeSampleUserManagerProxy? = null
    internal var dedicatedUserServiceConnection: PrivilegeUserServiceConnection? = null
    internal var embeddedUserServiceConnection: PrivilegeUserServiceConnection? = null
    internal var dedicatedUserService: IPrivilegeSampleDedicatedUserService? = null
    internal var embeddedUserService: IPrivilegeSampleEmbeddedUserService? = null
    @Volatile
    internal var shizukuExternalStarter: PrivilegeSampleShizukuExternalStarter? = null
    internal var startNotificationPairingAfterPermission = false
    internal var startShizukuExternalAfterPermission = false
    private val pairingEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleNotificationPairingEvent(intent)
        }
    }
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuStatus(append = false)
    }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        handleShizukuBinderDead()
    }
    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            handleShizukuPermissionResult(requestCode, grantResult)
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                privilegeUiViewModel.refreshExternalStartStatus(SAMPLE_SHIZUKU_EXTERNAL_START_ID)
            }
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            handleNotificationPermissionResult(granted)
    }
    internal val manualShellCommandLine: String by lazy(LazyThreadSafetyMode.NONE) {
        PrivilegeRuntime.createShellStartCommand().toSampleHostAdbShellCommand()
    }
    internal var screenState: PrivilegeSampleScreenState
        get() = sampleViewModel.screenState
        set(value) {
            sampleViewModel.screenState = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sampleViewModel = ViewModelProvider(this)[PrivilegeSampleViewModel::class.java]
        privilegeUiViewModel = ViewModelProvider(this)[PrivilegeUiViewModel::class.java]
        privilegeUiConfig = createPrivilegeSampleUiConfig(this)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        initializePrivilegeSample()
        setContent {
            val notificationPairingRunning =
                PrivilegeSampleAdbPairingService.running.collectAsState().value
            PrivilegeSampleScreen(
                state = screenState,
                backStack = sampleViewModel.backStack,
                selectedStartupTab = sampleViewModel.selectedStartupTab,
                privilegeUiConfig = privilegeUiConfig,
                privilegeUiViewModel = privilegeUiViewModel,
                notificationPairingRunning = notificationPairingRunning,
                onDestinationSelected = { sampleViewModel.selectDestination(it) },
                onStartupTabSelected = { sampleViewModel.selectStartupTab(it) },
                onOpenPrivilegeUi = { sampleViewModel.openPrivilegeUi() },
                onPrivilegeUiBack = { sampleViewModel.navigateBack() },
                onPrivilegeUiHelp = {},
                onPrivilegeUiConnected = { handlePrivilegeUiConnected(it) },
                onPrivilegeUiNotificationPermissionRequired = { requestPrivilegeUiNotificationPermission() },
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

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PrivilegeSampleAdbPairingService.ACTION_PAIRING_EVENT)
        ContextCompat.registerReceiver(
            this,
            pairingEventReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(pairingEventReceiver)
        super.onStop()
    }

    private fun handleNotificationPermissionResult(granted: Boolean) {
        val shouldStartSamplePairing = startNotificationPairingAfterPermission
        startNotificationPairingAfterPermission = false
        privilegeUiViewModel.handleNotificationPermissionResult(granted)
        if (granted && shouldStartSamplePairing) {
            startNotificationPairing()
        } else if (!granted && shouldStartSamplePairing) {
            screenState = screenState.copy(
                pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
                pairingMessage = "Notification permission is required to enter the pairing code from a notification.",
                message = "Notification permission not granted",
            )
        }
    }

    internal fun requestNotificationPermission() {
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
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        super.onDestroy()
    }

    private fun requestPrivilegeUiNotificationPermission() {
        requestNotificationPermission()
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
