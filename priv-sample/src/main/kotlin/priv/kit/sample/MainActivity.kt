package priv.kit.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import priv.kit.runtime.PrivilegeRuntime
import priv.kit.userservice.PrivilegeUserServiceConnection
import java.io.Closeable
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var sampleViewModel: PrivilegeSampleViewModel
    internal val executor = Executors.newSingleThreadExecutor()
    internal var readyServerWatcher: Closeable? = null
    internal var serverDisconnectedWatcher: Closeable? = null
    internal var dedicatedUserServiceStatusWatcher: Closeable? = null
    internal var embeddedUserServiceStatusWatcher: Closeable? = null
    internal var sampleUserManager: PrivilegeSampleUserManagerProxy? = null
    internal var dedicatedUserServiceConnection: PrivilegeUserServiceConnection? = null
    internal var embeddedUserServiceConnection: PrivilegeUserServiceConnection? = null
    internal var dedicatedUserService: IPrivilegeSampleDedicatedUserService? = null
    internal var embeddedUserService: IPrivilegeSampleEmbeddedUserService? = null
    internal var startNotificationPairingAfterPermission = false
    private val pairingEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleNotificationPairingEvent(intent)
        }
    }
    internal val manualShellCommandLine: String by lazy(LazyThreadSafetyMode.NONE) {
        PrivilegeRuntime.createManualShellCommand().commandLine
    }
    internal var screenState: PrivilegeSampleScreenState
        get() = sampleViewModel.screenState
        set(value) {
            sampleViewModel.screenState = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sampleViewModel = ViewModelProvider(this)[PrivilegeSampleViewModel::class.java]
        initializePrivilegeSample()
        setContent {
            PrivilegeSampleScreen(
                state = screenState,
                backStack = sampleViewModel.backStack,
                onDestinationSelected = { sampleViewModel.selectDestination(it) },
                onAdbDeviceNameChanged = { updateAdbDeviceName(it) },
                onRefreshAdbFingerprint = { refreshAdbFingerprint() },
                onCheckAdbPairing = { checkWirelessAdbPairing(showBusy = true) },
                onPairingCodeChanged = { updatePairingCode(it) },
                onTcpPortChanged = { updateTcpPort(it) },
                onStartRootRuntime = { startRootRuntime() },
                onCopyManualCommand = { copyManualShellCommand() },
                onPairWirelessAdb = { pairWirelessAdb() },
                onStartNotificationPairing = { startNotificationPairing() },
                onStartWirelessAdb = { startWirelessAdb() },
                onSwitchToTcp = { switchToTcp() },
                onRestartTcp = { restartTcp() },
                onStopTcp = { stopTcp() },
                onStopServer = { stopServer() },
                onGetUserManager = { getUserManagerBinder() },
                onGetUsers = { getUserManagerUsers() },
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

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST_CODE) return

        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted && startNotificationPairingAfterPermission) {
            startNotificationPairingAfterPermission = false
            startNotificationPairing()
        } else {
            startNotificationPairingAfterPermission = false
            screenState = screenState.copy(
                pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
                pairingMessage = "Notification permission is required to enter the pairing code from a notification.",
                message = "Notification permission not granted",
            )
        }
    }

    override fun onDestroy() {
        releasePrivilegeSample()
        super.onDestroy()
    }
}
