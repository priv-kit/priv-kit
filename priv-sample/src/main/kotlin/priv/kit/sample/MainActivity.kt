package priv.kit.sample

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.core.PrivilegeServerInfo
import priv.kit.runtime.PrivilegeManualShellConnection
import priv.kit.runtime.PrivilegeRuntime
import priv.kit.runtime.PrivilegeSession
import java.io.File
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var session: PrivilegeSession? = null
    private var manualShellConnection: PrivilegeManualShellConnection? = null
    private var readyServerWatcher: Closeable? = null
    private var screenState by mutableStateOf(PrivilegeSampleScreenState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val adbDeviceNameOverride = loadAdbDeviceNameOverride()
        screenState = screenState.copy(
            adbDeviceNameText = adbDeviceNameOverride,
            adbDeviceName = adbDeviceNameOverride.ifBlank { defaultAdbDeviceName() },
        )
        setContent {
            PrivilegeSampleScreen(
                state = screenState,
                onPageSelected = { screenState = screenState.copy(page = it) },
                onAdbDeviceNameChanged = ::updateAdbDeviceName,
                onPairingPortChanged = {
                    screenState = screenState.copy(
                        pairingPortText = it,
                        pairingStatus = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.FAILED) {
                            PrivilegeAdbPairingStatus.NOT_PAIRED
                        } else {
                            screenState.pairingStatus
                        },
                        pairingMessage = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.FAILED) {
                            "Enter the pairing code shown by Wireless debugging."
                        } else {
                            screenState.pairingMessage
                        },
                    )
                },
                onConnectPortChanged = { screenState = screenState.copy(connectPortText = it) },
                onRefreshAdbFingerprint = ::refreshAdbFingerprint,
                onCheckAdbPairing = { checkWirelessAdbPairing(showBusy = true) },
                onPairingCodeChanged = {
                    screenState = screenState.copy(
                        pairingCode = it,
                        pairingStatus = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.FAILED) {
                            PrivilegeAdbPairingStatus.NOT_PAIRED
                        } else {
                            screenState.pairingStatus
                        },
                        pairingMessage = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.FAILED) {
                            "Enter the pairing code shown by Wireless debugging."
                        } else {
                            screenState.pairingMessage
                        },
                    )
                },
                onTcpPortChanged = { screenState = screenState.copy(tcpPortText = it) },
                onStartRootRuntime = ::startRootRuntime,
                onPrepareManualShell = ::prepareManualShell,
                onCopyManualCommand = ::copyManualShellCommand,
                onCancelManualShell = ::cancelManualShell,
                onDiscoverPairingPort = ::discoverPairingPort,
                onPairWirelessAdb = ::pairWirelessAdb,
                onDiscoverConnectPort = ::discoverConnectPort,
                onStartWirelessAdb = ::startWirelessAdb,
                onSwitchToTcp = ::switchToTcp,
                onRestartTcp = ::restartTcp,
                onStopTcp = ::stopTcp,
                onStopServer = ::stopServer,
                onClearLog = { screenState = screenState.copy(logText = "") },
                onCopyLog = ::copySessionLog,
            )
        }
        watchReadyServers()
        refreshAdbFingerprint()
        checkWirelessAdbPairing(showBusy = false)
    }

    private fun watchReadyServers() {
        readyServerWatcher?.close()
        readyServerWatcher = PrivilegeRuntime.create(applicationContext).watchReadyServers(
            onReady = { newSession ->
                runOnUiThread {
                    connectSession(newSession, commandLine = null)
                    appendLog(
                        "Connected from server handshake: uid=${newSession.serverInfo.uid}, " +
                            "pid=${newSession.serverInfo.pid}, mode=${newSession.serverInfo.mode}",
                    )
                }
            },
            onFailure = { throwable ->
                runOnUiThread {
                    appendLog("Ready server handshake error: ${throwable.message ?: throwable.javaClass.name}")
                    appendLog(throwable.toDiagnosticString())
                }
            },
        )
    }

    private fun updateAdbDeviceName(value: String) {
        saveAdbDeviceNameOverride(value)
        screenState = screenState.copy(
            adbDeviceNameText = value,
            adbDeviceName = value.trim().ifBlank { defaultAdbDeviceName() },
            pairingMessage = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.NOT_PAIRED) {
                "ADB name updated. The owner-token key is unchanged."
            } else {
                screenState.pairingMessage
            },
        )
    }

    private fun refreshAdbFingerprint() {
        if (screenState.adbKeyFingerprintLoading) return
        val adbDeviceName = currentAdbDeviceNameOverride()
        screenState = screenState.copy(
            adbKeyFingerprintLoading = true,
            pairingMessage = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.NOT_PAIRED) {
                "Loading fingerprint for the owner-token identity..."
            } else {
                screenState.pairingMessage
            },
        )
        executor.execute {
            try {
                val info = createAdbStarter(adbDeviceName).getIdentityInfo()
                runOnUiThread {
                    screenState = screenState.copy(
                        adbDeviceName = info.identity.deviceName,
                        adbKeyFingerprint = info.publicKeyFingerprint,
                        adbKeyFingerprintLoading = false,
                        pairingMessage = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.NOT_PAIRED) {
                            "Fingerprint loaded. Pair this identity before starting."
                        } else {
                            screenState.pairingMessage
                        },
                    )
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    val message = throwable.message ?: throwable.javaClass.name
                    screenState = screenState.copy(
                        adbKeyFingerprint = null,
                        adbKeyFingerprintLoading = false,
                        pairingStatus = PrivilegeAdbPairingStatus.FAILED,
                        pairingMessage = message,
                    )
                    appendLog("Fingerprint error: $message")
                }
            }
        }
    }

    private fun checkWirelessAdbPairing(showBusy: Boolean) {
        if (showBusy && screenState.busy) return

        val portText = screenState.connectPortText.trim()
        val port = if (portText.isBlank()) null else portText.toIntOrNull()
        if (portText.isNotBlank() && port == null) {
            screenState = screenState.copy(
                message = "Connect port must be a number",
                pairingStatus = PrivilegeAdbPairingStatus.FAILED,
                pairingMessage = "Connect port must be a number.",
            )
            return
        }

        val adbDeviceName = currentAdbDeviceNameOverride()
        val message = if (port == null) {
            "Checking Wireless ADB pairing by discovering connect port..."
        } else {
            "Checking Wireless ADB pairing on port $port..."
        }
        screenState = screenState.copy(
            busy = if (showBusy) true else screenState.busy,
            pairingStatus = PrivilegeAdbPairingStatus.CHECKING,
            pairingMessage = message,
            message = if (showBusy) message else screenState.message,
        )
        appendLog(message)

        executor.execute {
            try {
                val result = createAdbStarter(adbDeviceName).checkPairing(
                    port = port,
                    discoverPort = port == null,
                )
                runOnUiThread {
                    val resultMessage = if (result.paired) {
                        "Current owner-token key is paired on ${result.host}:${result.port}."
                    } else {
                        "Current owner-token key is not paired" +
                            (result.failureMessage?.let { ": $it" } ?: ".")
                    }
                    screenState = screenState.copy(
                        busy = if (showBusy) false else screenState.busy,
                        pairingStatus = if (result.paired) {
                            PrivilegeAdbPairingStatus.PAIRED
                        } else {
                            PrivilegeAdbPairingStatus.NOT_PAIRED
                        },
                        pairingMessage = resultMessage,
                        connectPortText = result.port?.toString() ?: screenState.connectPortText,
                        adbDeviceName = result.identity.deviceName,
                        adbKeyFingerprint = result.publicKeyFingerprint,
                        adbKeyFingerprintLoading = false,
                        message = if (showBusy) resultMessage else screenState.message,
                    )
                    appendLog(resultMessage)
                    appendLog(result.output.text())
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    val failureMessage = throwable.message ?: throwable.javaClass.name
                    screenState = screenState.copy(
                        busy = if (showBusy) false else screenState.busy,
                        pairingStatus = PrivilegeAdbPairingStatus.FAILED,
                        pairingMessage = failureMessage,
                        message = if (showBusy) failureMessage else screenState.message,
                    )
                    appendLog("Pairing check error: $failureMessage")
                    appendLog(throwable.toDiagnosticString())
                }
            }
        }
    }

    private fun currentAdbDeviceNameOverride(): String? =
        screenState.adbDeviceNameText.trim().ifBlank { null }

    private fun createAdbStarter(adbDeviceName: String? = currentAdbDeviceNameOverride()): PrivilegeAdbStarter =
        PrivilegeRuntime.create(applicationContext).createAdbStarter(adbDeviceName = adbDeviceName)

    private fun loadAdbDeviceNameOverride(): String =
        runCatching {
            val file = adbDeviceNameConfigFile()
            if (file.isFile) file.readText(StandardCharsets.UTF_8).trim() else ""
        }.getOrDefault("")

    private fun saveAdbDeviceNameOverride(value: String) {
        val file = adbDeviceNameConfigFile()
        val directory = file.parentFile ?: return
        val trimmedValue = value.trim()
        runCatching {
            if (trimmedValue.isBlank()) {
                file.delete()
                return
            }
            if (!directory.exists()) directory.mkdirs()
            file.writeText(trimmedValue + "\n", StandardCharsets.UTF_8)
        }.onFailure { throwable ->
            appendLog("Failed to save ADB device name: ${throwable.message ?: throwable.javaClass.name}")
        }
    }

    private fun adbDeviceNameConfigFile(): File =
        File(File(filesDir, SAMPLE_CONFIG_DIRECTORY), ADB_DEVICE_NAME_FILE)

    private fun defaultAdbDeviceName(): String =
        runCatching {
            applicationInfo.loadLabel(packageManager).toString()
        }.getOrNull().toSampleAdbDeviceName()
            ?: packageName.toSampleAdbDeviceName()
            ?: DEFAULT_ADB_DEVICE_NAME

    private fun startRootRuntime() {
        runSessionStart("Starting Root Runtime...") {
            PrivilegeRuntime.create(applicationContext).startRoot()
        }
    }

    private fun prepareManualShell() {
        if (screenState.busy) return

        val connection = PrivilegeRuntime.create(applicationContext).prepareManualShell()
        manualShellConnection = connection
        screenState = screenState.copy(
            busy = true,
            status = PrivilegeSampleStatus.WAITING,
            manualShellCommandLine = connection.command.commandLine,
            message = "Run the starter command inside adb shell. Waiting for Binder...",
        )
        appendLog("Manual shell command prepared")

        executor.execute {
            try {
                val newSession = connection.awaitSession()
                runOnUiThread {
                    if (manualShellConnection === connection) {
                        connectSession(newSession, commandLine = connection.command.commandLine)
                    }
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    if (manualShellConnection === connection) {
                        connection.cancel()
                        manualShellConnection = null
                        setFailure(throwable)
                    }
                }
            }
        }
    }

    private fun discoverPairingPort() {
        val adbDeviceName = currentAdbDeviceNameOverride()
        screenState = screenState.copy(
            pairingStatus = PrivilegeAdbPairingStatus.SEARCHING,
            pairingMessage = "Searching for the Wireless debugging pairing service...",
        )
        runBusy(
            message = "Discovering ADB pairing port...",
            action = { createAdbStarter(adbDeviceName).discoverPairingPort() },
            onFailure = {
                screenState = screenState.copy(
                    pairingStatus = PrivilegeAdbPairingStatus.FAILED,
                    pairingMessage = it.message ?: it.javaClass.name,
                )
            },
        ) { port ->
            screenState = screenState.copy(
                pairingPortText = port.toString(),
                pairingStatus = PrivilegeAdbPairingStatus.FOUND,
                pairingMessage = "Pairing service found on port $port. Enter the pairing code to pair.",
            )
            "Pairing port: $port"
        }
    }

    private fun pairWirelessAdb() {
        val portText = screenState.pairingPortText.trim()
        val port = if (portText.isBlank()) null else portText.toIntOrNull()
        val code = screenState.pairingCode.trim()
        val adbDeviceName = currentAdbDeviceNameOverride()
        if (portText.isNotBlank() && port == null) {
            screenState = screenState.copy(
                message = "Pairing port must be a number",
                pairingStatus = PrivilegeAdbPairingStatus.FAILED,
                pairingMessage = "Pairing port must be a number.",
            )
            return
        }
        if (code.isBlank()) {
            screenState = screenState.copy(
                message = "Pairing code is required",
                pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
                pairingMessage = "Enter the pairing code shown by Wireless debugging.",
            )
            return
        }

        screenState = screenState.copy(
            pairingStatus = if (port == null) {
                PrivilegeAdbPairingStatus.SEARCHING
            } else {
                PrivilegeAdbPairingStatus.PAIRING
            },
            pairingMessage = if (port == null) {
                "Searching for the pairing service before pairing..."
            } else {
                "Pairing with Wireless debugging on port $port..."
            },
        )
        runBusy(
            message = if (port == null) {
                "Discovering ADB pairing port and pairing..."
            } else {
                "Pairing with wireless ADB on port $port..."
            },
            action = {
                createAdbStarter(adbDeviceName).pair(pairingCode = code, port = port)
            },
            onFailure = {
                screenState = screenState.copy(
                    pairingStatus = PrivilegeAdbPairingStatus.FAILED,
                    pairingMessage = it.message ?: it.javaClass.name,
                )
            },
        ) { result ->
            screenState = screenState.copy(
                pairingPortText = result.port.toString(),
                adbDeviceName = result.identity.deviceName,
                adbKeyFingerprint = result.publicKeyFingerprint,
                adbKeyFingerprintLoading = false,
                pairingStatus = PrivilegeAdbPairingStatus.PAIRED,
                pairingMessage = "Paired as ${result.identity.deviceName} on port ${result.port}.",
            )
            "Wireless ADB paired on port ${result.port}"
        }
    }

    private fun discoverConnectPort() {
        val adbDeviceName = currentAdbDeviceNameOverride()
        runBusy(
            message = "Discovering ADB connect port...",
            action = { createAdbStarter(adbDeviceName).discoverConnectPort() },
        ) { port ->
            screenState = screenState.copy(connectPortText = port.toString())
            "Connect port: $port"
        }
    }

    private fun startWirelessAdb() {
        val port = screenState.connectPortText.toIntOrNull()
        val adbDeviceName = currentAdbDeviceNameOverride()
        runSessionStart("Starting through Wireless ADB...") {
            PrivilegeRuntime.create(applicationContext).startAdb(
                options = PrivilegeAdbStartOptions(
                    port = port,
                    discoverPort = port == null,
                ),
                adbDeviceName = adbDeviceName,
            )
        }
    }

    private fun switchToTcp() {
        val connectPort = screenState.connectPortText.toIntOrNull()
        val tcpPort = screenState.tcpPortText.toIntOrNull() ?: PrivilegeAdbStartOptions.DEFAULT_TCP_PORT
        val adbDeviceName = currentAdbDeviceNameOverride()
        runBusy(
            message = "Switching ADB to TCP port $tcpPort...",
            action = {
                val starter = createAdbStarter(adbDeviceName)
                val activeConnectPort = connectPort ?: starter.discoverConnectPort()
                starter.switchToTcp(currentPort = activeConnectPort, tcpPort = tcpPort)
            },
        ) {
            screenState = screenState.copy(connectPortText = tcpPort.toString())
            "ADB TCP mode requested on port $tcpPort"
        }
    }

    private fun restartTcp() {
        val tcpPort = screenState.tcpPortText.toIntOrNull() ?: PrivilegeAdbStartOptions.DEFAULT_TCP_PORT
        val adbDeviceName = currentAdbDeviceNameOverride()
        runSessionStart("Restarting through ADB TCP port $tcpPort...") {
            PrivilegeRuntime.create(applicationContext).startAdb(
                options = PrivilegeAdbStartOptions(
                    tcpMode = true,
                    tcpPort = tcpPort,
                    discoverPort = false,
                ),
                adbDeviceName = adbDeviceName,
            )
        }
    }

    private fun stopTcp() {
        val tcpPort = screenState.tcpPortText.toIntOrNull() ?: PrivilegeAdbStartOptions.DEFAULT_TCP_PORT
        val adbDeviceName = currentAdbDeviceNameOverride()
        runBusy(
            message = "Stopping ADB TCP mode...",
            action = {
                createAdbStarter(adbDeviceName).stopTcp(tcpPort = tcpPort)
            },
        ) {
            "ADB TCP mode stop requested"
        }
    }

    private fun stopServer() {
        if (screenState.busy) return
        val activeSession = session
        if (activeSession == null) {
            screenState = screenState.copy(message = "No server connected")
            appendLog("No server connected")
            return
        }

        screenState = screenState.copy(
            busy = true,
            message = "Stopping Privileged Server...",
        )
        appendLog("Stopping Privileged Server...")

        executor.execute {
            try {
                activeSession.setOnDisconnectedListener(null)
                activeSession.shutdown()
                runOnUiThread {
                    if (session === activeSession) {
                        session = null
                        screenState = screenState.copy(
                            busy = false,
                            status = PrivilegeSampleStatus.DISCONNECTED,
                            serverInfo = null,
                            message = "Server stopped",
                        )
                        appendLog("Server stopped")
                    }
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    if (session === activeSession) {
                        setFailure(throwable)
                    }
                }
            }
        }
    }

    private fun runSessionStart(
        message: String,
        start: () -> PrivilegeSession,
    ) {
        if (screenState.busy) return
        manualShellConnection?.cancel()
        manualShellConnection = null
        screenState = screenState.copy(
            busy = true,
            status = PrivilegeSampleStatus.STARTING,
            serverInfo = null,
            message = message,
        )
        appendLog(message)

        executor.execute {
            try {
                val newSession = start()
                runOnUiThread {
                    connectSession(newSession, commandLine = null)
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    setFailure(throwable)
                }
            }
        }
    }

    private fun <T> runBusy(
        message: String,
        action: () -> T,
        onFailure: ((Throwable) -> Unit)? = null,
        onSuccess: (T) -> String,
    ) {
        if (screenState.busy) return
        screenState = screenState.copy(
            busy = true,
            message = message,
        )
        appendLog(message)

        executor.execute {
            try {
                val result = action()
                runOnUiThread {
                    val resultMessage = onSuccess(result)
                    screenState = screenState.copy(
                        busy = false,
                        message = resultMessage,
                    )
                    appendLog(resultMessage)
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    onFailure?.invoke(throwable)
                    setFailure(throwable)
                }
            }
        }
    }

    private fun connectSession(
        session: PrivilegeSession,
        commandLine: String?,
    ) {
        this.session?.setOnDisconnectedListener(null)
        this.session = session
        manualShellConnection = null
        session.setOnDisconnectedListener {
            runOnUiThread {
                if (this.session === it) {
                    screenState = screenState.copy(
                        busy = false,
                        status = PrivilegeSampleStatus.DISCONNECTED,
                        serverInfo = null,
                        message = "Binder died",
                    )
                    appendLog("Binder died")
                }
            }
        }
        screenState = screenState.copy(
            busy = false,
            status = PrivilegeSampleStatus.CONNECTED,
            serverInfo = session.serverInfo,
            manualShellCommandLine = commandLine ?: screenState.manualShellCommandLine,
            message = "Connected",
        )
        appendLog("Connected: uid=${session.serverInfo.uid}, pid=${session.serverInfo.pid}, mode=${session.serverInfo.mode}")
    }

    private fun setFailure(throwable: Throwable) {
        val message = throwable.message ?: throwable.javaClass.name
        screenState = screenState.copy(
            busy = false,
            status = PrivilegeSampleStatus.DISCONNECTED,
            serverInfo = null,
            message = message,
        )
        appendLog("Error: $message")
        appendLog(throwable.toDiagnosticString())
    }

    private fun appendLog(line: String) {
        val nextLog = if (screenState.logText.isBlank()) {
            line
        } else {
            screenState.logText + "\n" + line
        }
        screenState = screenState.copy(logText = nextLog.takeLast(MAX_LOG_CHARS))
    }

    private fun copyManualShellCommand() {
        val commandLine = screenState.manualShellCommandLine ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Priv Kit manual shell command", commandLine),
        )
        screenState = screenState.copy(message = "Manual shell command copied")
    }

    private fun cancelManualShell() {
        val connection = manualShellConnection ?: return
        connection.cancel()
        manualShellConnection = null
        screenState = screenState.copy(
            busy = false,
            status = PrivilegeSampleStatus.DISCONNECTED,
            serverInfo = null,
            manualShellCommandLine = null,
            message = "Manual shell canceled",
        )
        appendLog("Manual shell command canceled")
    }

    private fun copySessionLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Priv Kit wireless ADB log", screenState.wirelessDebugLogText()),
        )
        screenState = screenState.copy(message = "Wireless ADB log copied")
    }

    override fun onDestroy() {
        readyServerWatcher?.close()
        readyServerWatcher = null
        manualShellConnection?.cancel()
        manualShellConnection = null
        executor.shutdownNow()
        session?.setOnDisconnectedListener(null)
        session = null
        super.onDestroy()
    }

    companion object {
        private const val MAX_LOG_CHARS = 32_000
        private const val SAMPLE_CONFIG_DIRECTORY = ".priv-kit"
        private const val ADB_DEVICE_NAME_FILE = "adb-device-name.txt"
        private const val DEFAULT_ADB_DEVICE_NAME = "priv-kit"
    }
}

private enum class PrivilegeSamplePage(val title: String) {
    ROOT("Root"),
    MANUAL("Manual"),
    ADB("Wireless ADB"),
    TCP("TCP"),
    SESSION("Session"),
}

private enum class PrivilegeSampleStatus {
    CONNECTED,
    DISCONNECTED,
    WAITING,
    STARTING,
}

private enum class PrivilegeAdbPairingStatus(val label: String) {
    NOT_PAIRED("Not paired"),
    CHECKING("Checking"),
    SEARCHING("Searching"),
    FOUND("Port found"),
    PAIRING("Pairing"),
    PAIRED("Paired"),
    FAILED("Failed"),
}

private data class PrivilegeSampleScreenState(
    val page: PrivilegeSamplePage = PrivilegeSamplePage.ROOT,
    val busy: Boolean = false,
    val status: PrivilegeSampleStatus = PrivilegeSampleStatus.DISCONNECTED,
    val serverInfo: PrivilegeServerInfo? = null,
    val manualShellCommandLine: String? = null,
    val adbDeviceNameText: String = "",
    val adbDeviceName: String = "",
    val adbKeyFingerprint: String? = null,
    val adbKeyFingerprintLoading: Boolean = false,
    val pairingPortText: String = "",
    val connectPortText: String = "",
    val pairingCode: String = "",
    val pairingStatus: PrivilegeAdbPairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
    val pairingMessage: String = "Enter the pairing code shown by Wireless debugging.",
    val tcpPortText: String = PrivilegeAdbStartOptions.DEFAULT_TCP_PORT.toString(),
    val message: String = "Ready",
    val logText: String = "",
)

private fun PrivilegeSampleScreenState.wirelessDebugLogText(): String =
    buildString {
        appendLine("Priv Kit Wireless ADB diagnostics")
        appendLine("page=$page")
        appendLine("busy=$busy")
        appendLine("runtimeStatus=$status")
        appendLine("message=$message")
        appendLine("pairingStatus=$pairingStatus")
        appendLine("pairingMessage=$pairingMessage")
        appendLine("adbDeviceNameConfigured=${adbDeviceNameText.ifBlank { "app-name" }}")
        appendLine("adbDeviceName=$adbDeviceName")
        appendLine("adbKeySource=owner-token")
        appendLine("adbKeyFingerprint=${adbKeyFingerprint ?: "not loaded"}")
        appendLine("pairingPort=${pairingPortText.ifBlank { "blank" }}")
        appendLine("connectPort=${connectPortText.ifBlank { "blank" }}")
        appendLine("tcpPort=${tcpPortText.ifBlank { "blank" }}")
        appendLine("serverInfo=${serverInfo ?: "none"}")
        appendLine()
        appendLine("Session log:")
        appendLine(logText.ifBlank { "<empty>" })
    }

@Composable
private fun PrivilegeSampleScreen(
    state: PrivilegeSampleScreenState,
    onPageSelected: (PrivilegeSamplePage) -> Unit,
    onAdbDeviceNameChanged: (String) -> Unit,
    onPairingPortChanged: (String) -> Unit,
    onConnectPortChanged: (String) -> Unit,
    onRefreshAdbFingerprint: () -> Unit,
    onCheckAdbPairing: () -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onTcpPortChanged: (String) -> Unit,
    onStartRootRuntime: () -> Unit,
    onPrepareManualShell: () -> Unit,
    onCopyManualCommand: () -> Unit,
    onCancelManualShell: () -> Unit,
    onDiscoverPairingPort: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onDiscoverConnectPort: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onSwitchToTcp: () -> Unit,
    onRestartTcp: () -> Unit,
    onStopTcp: () -> Unit,
    onStopServer: () -> Unit,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F7F9))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText(
            text = "Priv Kit Sample",
            style = TextStyle(
                color = Color(0xFF101418),
                fontFamily = FontFamily.SansSerif,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )

        PageTabs(
            selectedPage = state.page,
            busy = state.busy,
            onPageSelected = onPageSelected,
        )

        StatusPanel(state, onStopServer)

        when (state.page) {
            PrivilegeSamplePage.ROOT -> RootPage(state, onStartRootRuntime)
            PrivilegeSamplePage.MANUAL -> ManualPage(
                state = state,
                onPrepareManualShell = onPrepareManualShell,
                onCopyManualCommand = onCopyManualCommand,
                onCancelManualShell = onCancelManualShell,
            )
            PrivilegeSamplePage.ADB -> WirelessAdbPage(
                state = state,
                onAdbDeviceNameChanged = onAdbDeviceNameChanged,
                onPairingPortChanged = onPairingPortChanged,
                onConnectPortChanged = onConnectPortChanged,
                onRefreshAdbFingerprint = onRefreshAdbFingerprint,
                onCheckAdbPairing = onCheckAdbPairing,
                onPairingCodeChanged = onPairingCodeChanged,
                onCopyLog = onCopyLog,
                onDiscoverPairingPort = onDiscoverPairingPort,
                onPairWirelessAdb = onPairWirelessAdb,
                onDiscoverConnectPort = onDiscoverConnectPort,
                onStartWirelessAdb = onStartWirelessAdb,
            )
            PrivilegeSamplePage.TCP -> TcpPage(
                state = state,
                onTcpPortChanged = onTcpPortChanged,
                onConnectPortChanged = onConnectPortChanged,
                onSwitchToTcp = onSwitchToTcp,
                onRestartTcp = onRestartTcp,
                onStopTcp = onStopTcp,
            )
            PrivilegeSamplePage.SESSION -> SessionPage(state, onClearLog, onCopyLog)
        }
    }
}

@Composable
private fun PageTabs(
    selectedPage: PrivilegeSamplePage,
    busy: Boolean,
    onPageSelected: (PrivilegeSamplePage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrivilegeSamplePage.entries.chunked(2).forEach { rowPages ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPages.forEach { page ->
                    val selected = page == selectedPage
                    SampleAction(
                        label = page.title,
                        enabled = !busy || selected,
                        background = if (selected) Color(0xFF1769E0) else Color(0xFFE2E7EE),
                        foreground = if (selected) Color.White else Color(0xFF27313B),
                        modifier = Modifier.weight(1f),
                    ) {
                        onPageSelected(page)
                    }
                }
                if (rowPages.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusPanel(
    state: PrivilegeSampleScreenState,
    onStopServer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(state.status, state.busy)
            BasicText(
                text = state.message,
                style = TextStyle(
                    color = Color(0xFF48525C),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                ),
            )
        }
        RuntimeInfoRow(label = "uid", value = state.serverInfo?.uid?.toString() ?: "-")
        RuntimeInfoRow(label = "pid", value = state.serverInfo?.pid?.toString() ?: "-")
        RuntimeInfoRow(label = "mode", value = state.serverInfo?.mode?.toString() ?: "-")
        RuntimeInfoRow(label = "protocol", value = state.serverInfo?.protocolVersion?.toString() ?: "-")
        RuntimeInfoRow(label = "server", value = state.serverInfo?.serverVersion ?: "-")
        SampleAction(
            label = "Stop Server",
            enabled = !state.busy && state.status == PrivilegeSampleStatus.CONNECTED,
            background = Color(0xFFB42318),
            onClick = onStopServer,
        )
    }
}

@Composable
private fun RootPage(
    state: PrivilegeSampleScreenState,
    onStartRootRuntime: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SampleAction(
            label = if (state.busy && state.status == PrivilegeSampleStatus.STARTING) {
                "Starting Root Runtime..."
            } else {
                "Start Root Runtime"
            },
            enabled = !state.busy,
            background = Color(0xFF1769E0),
            onClick = onStartRootRuntime,
        )
    }
}

@Composable
private fun ManualPage(
    state: PrivilegeSampleScreenState,
    onPrepareManualShell: () -> Unit,
    onCopyManualCommand: () -> Unit,
    onCancelManualShell: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SampleAction(
            label = if (state.busy && state.status == PrivilegeSampleStatus.WAITING) {
                "Waiting for Manual Shell..."
            } else {
                "Prepare Manual Shell Command"
            },
            enabled = !state.busy,
            background = Color(0xFF2F5D50),
            onClick = onPrepareManualShell,
        )
        state.manualShellCommandLine?.let { commandLine ->
            CommandBlock(commandLine = commandLine, onCopy = onCopyManualCommand)
            if (state.busy && state.status == PrivilegeSampleStatus.WAITING) {
                SampleAction(
                    label = "Cancel Manual Shell",
                    enabled = true,
                    background = Color(0xFFB42318),
                    onClick = onCancelManualShell,
                )
            }
        }
    }
}

@Composable
private fun PairingStatusPanel(
    status: PrivilegeAdbPairingStatus,
    message: String,
    fingerprint: String?,
    fingerprintLoading: Boolean,
) {
    val background = when (status) {
        PrivilegeAdbPairingStatus.PAIRED -> Color(0xFFE4F4EA)
        PrivilegeAdbPairingStatus.FAILED -> Color(0xFFFFECE8)
        PrivilegeAdbPairingStatus.CHECKING,
        PrivilegeAdbPairingStatus.SEARCHING,
        PrivilegeAdbPairingStatus.PAIRING,
        PrivilegeAdbPairingStatus.FOUND,
        -> Color(0xFFEAF1FF)
        PrivilegeAdbPairingStatus.NOT_PAIRED -> Color(0xFFF1F3F5)
    }
    val foreground = when (status) {
        PrivilegeAdbPairingStatus.PAIRED -> Color(0xFF16743A)
        PrivilegeAdbPairingStatus.FAILED -> Color(0xFFB42318)
        PrivilegeAdbPairingStatus.CHECKING,
        PrivilegeAdbPairingStatus.SEARCHING,
        PrivilegeAdbPairingStatus.PAIRING,
        PrivilegeAdbPairingStatus.FOUND,
        -> Color(0xFF1769E0)
        PrivilegeAdbPairingStatus.NOT_PAIRED -> Color(0xFF48525C)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(foreground),
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(
                text = "Pairing: ${status.label}",
                style = TextStyle(
                    color = foreground,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        BasicText(
            text = message,
            style = TextStyle(
                color = Color(0xFF27313B),
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        )
        BasicText(
            text = "Fingerprint: " + when {
                fingerprintLoading -> "loading..."
                fingerprint != null -> fingerprint
                else -> "not loaded"
            },
            style = TextStyle(
                color = Color(0xFF27313B),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
        )
    }
}

@Composable
private fun WirelessAdbPage(
    state: PrivilegeSampleScreenState,
    onAdbDeviceNameChanged: (String) -> Unit,
    onPairingPortChanged: (String) -> Unit,
    onConnectPortChanged: (String) -> Unit,
    onRefreshAdbFingerprint: () -> Unit,
    onCheckAdbPairing: () -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onCopyLog: () -> Unit,
    onDiscoverPairingPort: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onDiscoverConnectPort: () -> Unit,
    onStartWirelessAdb: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PairingStatusPanel(
            status = state.pairingStatus,
            message = state.pairingMessage,
            fingerprint = state.adbKeyFingerprint,
            fingerprintLoading = state.adbKeyFingerprintLoading,
        )
        SampleField("ADB device name (blank = app name)", state.adbDeviceNameText, onAdbDeviceNameChanged)
        RuntimeInfoRow(label = "adb name", value = state.adbDeviceName)
        RuntimeInfoRow(label = "key source", value = "owner-token")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = "Refresh Identity",
                enabled = !state.busy && !state.adbKeyFingerprintLoading,
                background = Color(0xFF5E6873),
                modifier = Modifier.weight(1f),
                onClick = onRefreshAdbFingerprint,
            )
            SampleAction(
                label = "Check Pairing",
                enabled = !state.busy && !state.adbKeyFingerprintLoading,
                background = Color(0xFF5E4FA2),
                modifier = Modifier.weight(1f),
                onClick = onCheckAdbPairing,
            )
        }
        SampleAction(
            label = "Copy Wireless Log",
            enabled = true,
            background = Color(0xFF2A3541),
            onClick = onCopyLog,
        )
        SampleField("Pairing code", state.pairingCode, onPairingCodeChanged)
        SampleField("Pairing port (optional)", state.pairingPortText, onPairingPortChanged)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = "Find Pairing Port",
                enabled = !state.busy,
                background = Color(0xFF5E4FA2),
                modifier = Modifier.weight(1f),
                onClick = onDiscoverPairingPort,
            )
            SampleAction(
                label = "Pair by Code",
                enabled = !state.busy,
                background = Color(0xFF1769E0),
                modifier = Modifier.weight(1f),
                onClick = onPairWirelessAdb,
            )
        }

        SampleField("Connect port", state.connectPortText, onConnectPortChanged)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = "Discover Connect Port",
                enabled = !state.busy,
                background = Color(0xFF5E6873),
                modifier = Modifier.weight(1f),
                onClick = onDiscoverConnectPort,
            )
            SampleAction(
                label = "Start Wireless ADB",
                enabled = !state.busy,
                background = Color(0xFF1769E0),
                modifier = Modifier.weight(1f),
                onClick = onStartWirelessAdb,
            )
        }
    }
}

@Composable
private fun TcpPage(
    state: PrivilegeSampleScreenState,
    onTcpPortChanged: (String) -> Unit,
    onConnectPortChanged: (String) -> Unit,
    onSwitchToTcp: () -> Unit,
    onRestartTcp: () -> Unit,
    onStopTcp: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SampleField("Current wireless port", state.connectPortText, onConnectPortChanged)
        SampleField("TCP port", state.tcpPortText, onTcpPortChanged)
        SampleAction(
            label = "Switch to TCP Mode",
            enabled = !state.busy,
            background = Color(0xFF5E4FA2),
            onClick = onSwitchToTcp,
        )
        SampleAction(
            label = "Restart From TCP Port",
            enabled = !state.busy,
            background = Color(0xFF1769E0),
            onClick = onRestartTcp,
        )
        SampleAction(
            label = "Stop TCP Mode",
            enabled = !state.busy,
            background = Color(0xFF66717D),
            onClick = onStopTcp,
        )
    }
}

@Composable
private fun SessionPage(
    state: PrivilegeSampleScreenState,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = "Copy Log",
                enabled = state.logText.isNotBlank(),
                background = Color(0xFF1769E0),
                modifier = Modifier.weight(1f),
                onClick = onCopyLog,
            )
            SampleAction(
                label = "Clear Log",
                enabled = !state.busy,
                background = Color(0xFF66717D),
                modifier = Modifier.weight(1f),
                onClick = onClearLog,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111820))
                .padding(16.dp),
        ) {
            BasicText(
                text = state.logText.ifBlank { "-" },
                style = TextStyle(
                    color = Color(0xFFF7FAFC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

private fun Throwable.toDiagnosticString(): String {
    val lines = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        lines += "Cause[$depth]: ${current.javaClass.name}: ${current.message.orEmpty()}"
        current.stackTrace.take(8).forEach { frame ->
            lines += "  at $frame"
        }
        current = current.cause
        depth++
    }
    return lines.joinToString("\n")
}

private fun String?.toSampleAdbDeviceName(): String? {
    val value = this
        ?.replace('\u0000', ' ')
        ?.replace('\r', ' ')
        ?.replace('\n', ' ')
        ?.trim()
        ?.take(128)
    return value?.ifBlank { null }
}

@Composable
private fun SampleField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = label,
            style = TextStyle(
                color = Color(0xFF5E6873),
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = Color(0xFF101418),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SampleAction(
    label: String,
    enabled: Boolean,
    background: Color,
    modifier: Modifier = Modifier,
    foreground: Color = Color.White,
    onClick: () -> Unit,
) {
    val actualBackground = if (enabled) background else Color(0xFF9DA8B5)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(actualBackground)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = foreground,
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun StatusPill(
    status: PrivilegeSampleStatus,
    busy: Boolean,
) {
    val text = when {
        busy -> "Busy"
        status == PrivilegeSampleStatus.CONNECTED -> "Connected"
        status == PrivilegeSampleStatus.WAITING -> "Waiting"
        status == PrivilegeSampleStatus.STARTING -> "Starting"
        else -> "Disconnected"
    }
    val background = when {
        busy -> Color(0xFFEAF1FF)
        status == PrivilegeSampleStatus.CONNECTED -> Color(0xFFE4F4EA)
        status == PrivilegeSampleStatus.WAITING -> Color(0xFFFFF4D9)
        else -> Color(0xFFF1F3F5)
    }
    val foreground = when {
        busy -> Color(0xFF1769E0)
        status == PrivilegeSampleStatus.CONNECTED -> Color(0xFF16743A)
        status == PrivilegeSampleStatus.WAITING -> Color(0xFF916300)
        else -> Color(0xFF48525C)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(foreground),
        )
        Spacer(modifier = Modifier.width(8.dp))
        BasicText(
            text = text,
            style = TextStyle(
                color = foreground,
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun CommandBlock(
    commandLine: String,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111820))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = "Run starter inside adb shell",
            style = TextStyle(
                color = Color(0xFFB8C4D0),
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        BasicText(
            text = commandLine,
            style = TextStyle(
                color = Color(0xFFF7FAFC),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
        )
        SampleAction(
            label = "Copy Command",
            enabled = true,
            background = Color(0xFF2A3541),
            onClick = onCopy,
        )
    }
}

@Composable
private fun RuntimeInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = Color(0xFF5E6873),
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
            ),
        )
        Spacer(modifier = Modifier.width(16.dp))
        BasicText(
            text = value,
            style = TextStyle(
                color = Color(0xFF101418),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
