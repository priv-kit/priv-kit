package priv.kit.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import priv.kit.runtime.PrivilegeRuntime
import priv.kit.runtime.PrivilegeSession
import java.io.Closeable
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    internal val executor = Executors.newSingleThreadExecutor()
    internal var session: PrivilegeSession? = null
    internal var readyServerWatcher: Closeable? = null
    internal var startNotificationPairingAfterPermission = false
    private val pairingEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleNotificationPairingEvent(intent)
        }
    }
    internal val manualShellCommandLine: String by lazy(LazyThreadSafetyMode.NONE) {
        PrivilegeRuntime.create(applicationContext).createManualShellCommand().commandLine
    }
    internal var screenState by mutableStateOf(PrivilegeSampleScreenState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializePrivilegeSample()
        setContent {
            PrivilegeSampleScreen(
                state = screenState,
                onPageSelected = { selectPage(it) },
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
                onClearLog = { clearLog() },
                onCopyLog = { copySessionLog() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PrivilegeSampleAdbPairingService.ACTION_PAIRING_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingEventReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pairingEventReceiver, filter)
        }
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
