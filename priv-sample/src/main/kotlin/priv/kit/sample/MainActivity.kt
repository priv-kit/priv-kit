package priv.kit.sample

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import priv.kit.sample.ui.stopPrivilegeSampleNotificationPairing
import priv.kit.ui.PrivilegeAdbPairingService
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationEvent
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    internal lateinit var sampleViewModel: PrivilegeSampleViewModel
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
        observeNotificationPairingEvents()
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

    private fun observeNotificationPairingEvents() {
        lifecycleScope.launch {
            PrivilegeAdbPairingService.notificationEvents.collect { event ->
                if (event.ownerId != sampleViewModel.notificationPairingOwnerId) return@collect
                when (event) {
                    is PrivilegeAdbPairingNotificationEvent.Submit -> {
                        stopPrivilegeSampleNotificationPairing(
                            this@MainActivity,
                            sampleViewModel.notificationPairingOwnerId,
                        )
                        screenState = screenState.copy(notificationPairingRunning = false)
                        updatePairingCode(event.pairingCode)
                        pairWirelessAdb()
                    }
                    is PrivilegeAdbPairingNotificationEvent.Stop -> {
                        val message = "Notification pairing stopped"
                        screenState = screenState.copy(
                            notificationPairingRunning = false,
                            pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
                            pairingMessage = message,
                            message = message,
                        )
                    }
                    is PrivilegeAdbPairingNotificationEvent.Unavailable -> {
                        screenState = screenState.copy(
                            notificationPairingRunning = false,
                            pairingMessage = event.message,
                            message = event.message,
                        )
                    }
                    is PrivilegeAdbPairingNotificationEvent.Detached -> {
                        screenState = screenState.copy(notificationPairingRunning = false)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handleShizukuHostVisible()
    }

    private fun handleNotificationPermissionResult(granted: Boolean) {
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

    internal fun requestNotificationPermission() {
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

    private fun createPrivilegeSampleCallbacks(): PrivilegeSampleCallbacks =
        PrivilegeSampleCallbacks(
            navigation = PrivilegeSampleNavigationCallbacks(
                destinationSelected = { sampleViewModel.selectDestination(it) },
                startupTabSelected = { sampleViewModel.selectStartupTab(it) },
            ),
            privilegeUi = PrivilegeSamplePrivilegeUiCallbacks(
                open = sampleViewModel::openPrivilegeUi,
                back = sampleViewModel::navigateBack,
                connected = sampleViewModel::handlePrivilegeUiConnected,
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
