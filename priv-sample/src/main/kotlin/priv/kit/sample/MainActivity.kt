package priv.kit.sample

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    internal lateinit var sampleViewModel: PrivilegeSampleViewModel
    private var notificationPermissionResultHandler: ((Boolean) -> Unit)? = null
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
                    callbacks = createPrivilegeSampleCallbacks(),
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

        val shouldStartSamplePairing = sampleViewModel.startNotificationPairingAfterPermission
        sampleViewModel.startNotificationPairingAfterPermission = false
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

    private fun createPrivilegeSampleCallbacks(): PrivilegeSampleCallbacks =
        PrivilegeSampleCallbacks(
            navigation = PrivilegeSampleNavigationCallbacks(
                destinationSelected = { sampleViewModel.selectDestination(it) },
                startupTabSelected = { sampleViewModel.selectStartupTab(it) },
            ),
            privilegeUi = PrivilegeSamplePrivilegeUiCallbacks(
                open = { sampleViewModel.openPrivilegeUi() },
                back = { sampleViewModel.navigateBack() },
                help = {},
                connected = { handlePrivilegeUiConnected(it) },
                notificationPermissionRequired = { requestPrivilegeUiNotificationPermission(it) },
                notificationPermissionDisposed = { clearPrivilegeUiNotificationPermissionHandler(it) },
            ),
            connection = PrivilegeSampleConnectionCallbacks(
                adbDeviceNameChanged = { updateAdbDeviceName(it) },
                refreshAdbFingerprint = { refreshAdbFingerprint() },
                checkAdbPairing = { checkWirelessAdbPairing(showBusy = true) },
                pairingCodeChanged = { updatePairingCode(it) },
                tcpPortChanged = { updateTcpPort(it) },
                startRootRuntime = { startRootRuntime() },
                copyManualCommand = { copyManualShellCommand() },
                startShizukuExternal = { startShizukuExternal() },
                pairWirelessAdb = { pairWirelessAdb() },
                startNotificationPairing = { startNotificationPairing() },
                stopNotificationPairing = { stopNotificationPairing() },
                startWirelessAdb = { startWirelessAdb() },
                switchToTcp = { switchToTcp() },
                restartTcp = { restartTcp() },
                stopTcp = { stopTcp() },
                stopServer = { stopServer() },
            ),
            binder = PrivilegeSampleBinderCallbacks(
                getUserManager = { getUserManagerBinder() },
                getUsers = { getUserManagerUsers() },
                runImqsNative = { runImqsNative() },
            ),
            userService = PrivilegeSampleUserServiceCallbacks(
                bindDedicated = { bindDedicatedUserService() },
                callDedicated = { callDedicatedUserService() },
                stopDedicated = { stopDedicatedUserService() },
                bindEmbedded = { bindEmbeddedUserService() },
                callEmbedded = { callEmbeddedUserService() },
                stopEmbedded = { stopEmbeddedUserService() },
            ),
            log = PrivilegeSampleLogCallbacks(
                clear = { clearLog() },
                copy = { copySessionLog() },
            ),
        )
}

internal var MainActivity.screenState: PrivilegeSampleScreenState
    get() = sampleViewModel.screenState
    set(value) {
        sampleViewModel.screenState = value
    }
