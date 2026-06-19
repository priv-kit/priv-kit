package priv.kit.sample

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

    override fun onDestroy() {
        releasePrivilegeSample()
        super.onDestroy()
    }
}
