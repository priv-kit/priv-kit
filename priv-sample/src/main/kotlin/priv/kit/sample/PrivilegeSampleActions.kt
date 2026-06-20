package priv.kit.sample

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.runtime.PrivilegeRuntime
import priv.kit.runtime.PrivilegeSession
import java.io.File
import java.nio.charset.StandardCharsets

internal fun MainActivity.initializePrivilegeSample() {
    val adbDeviceNameOverride = loadAdbDeviceNameOverride()
    val manualShellCommandResult = runCatching { manualShellCommandLine }
    screenState = screenState.copy(
        adbDeviceNameText = adbDeviceNameOverride,
        adbDeviceName = adbDeviceNameOverride.ifBlank { defaultAdbDeviceName() },
        manualShellCommandLine = manualShellCommandResult.getOrNull(),
    )
    manualShellCommandResult.exceptionOrNull()?.let { throwable ->
        val message = throwable.message ?: throwable.javaClass.name
        screenState = screenState.copy(message = message)
        appendLog("Manual shell command error: $message")
        appendLog(throwable.toDiagnosticString())
    }
    watchReadyServers()
    refreshAdbFingerprint()
    checkWirelessAdbPairing(showBusy = false)
}

internal fun MainActivity.selectPage(page: PrivilegeSamplePage) {
    screenState = screenState.copy(page = page)
}

internal fun MainActivity.updatePairingCode(value: String) {
    val pairingCode = value.toPairingCodeDigits()
    screenState = screenState.copy(
        pairingCode = pairingCode,
        pairingStatus = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.FAILED) {
            PrivilegeAdbPairingStatus.NOT_PAIRED
        } else {
            screenState.pairingStatus
        },
        pairingMessage = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.FAILED) {
            DEFAULT_PAIRING_MESSAGE
        } else {
            screenState.pairingMessage
        },
    )
}

internal fun MainActivity.updateTcpPort(value: String) {
    screenState = screenState.copy(tcpPortText = value)
}

internal fun MainActivity.clearLog() {
    screenState = screenState.copy(logText = "")
}

internal fun MainActivity.releasePrivilegeSample() {
    readyServerWatcher?.close()
    readyServerWatcher = null
    executor.shutdownNow()
    session?.setOnDisconnectedListener(null)
    session = null
}

internal fun MainActivity.watchReadyServers() {
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

internal fun MainActivity.updateAdbDeviceName(value: String) {
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

internal fun MainActivity.refreshAdbFingerprint() {
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

internal fun MainActivity.checkWirelessAdbPairing(showBusy: Boolean) {
    if (showBusy && screenState.busy) return

    val adbDeviceName = currentAdbDeviceNameOverride()
    val message = "Checking Wireless ADB pairing by discovering the connect port..."
    screenState = screenState.copy(
        busy = if (showBusy) true else screenState.busy,
        pairingStatus = PrivilegeAdbPairingStatus.CHECKING,
        pairingMessage = message,
        message = if (showBusy) message else screenState.message,
    )
    appendLog(message)

    executor.execute {
        try {
            val result = createAdbStarter(adbDeviceName).checkPairing()
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

private fun MainActivity.currentAdbDeviceNameOverride(): String? =
    screenState.adbDeviceNameText.trim().ifBlank { null }

private fun MainActivity.createAdbStarter(adbDeviceName: String? = currentAdbDeviceNameOverride()): PrivilegeAdbStarter =
    PrivilegeRuntime.create(applicationContext).createAdbStarter(adbDeviceName = adbDeviceName)

private fun MainActivity.loadAdbDeviceNameOverride(): String =
    runCatching {
        val file = adbDeviceNameConfigFile()
        if (file.isFile) file.readText(StandardCharsets.UTF_8).trim() else ""
    }.getOrDefault("")

private fun MainActivity.saveAdbDeviceNameOverride(value: String) {
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

private fun MainActivity.adbDeviceNameConfigFile(): File =
    File(File(filesDir, SAMPLE_CONFIG_DIRECTORY), ADB_DEVICE_NAME_FILE)

private fun MainActivity.defaultAdbDeviceName(): String =
    runCatching {
        applicationInfo.loadLabel(packageManager).toString()
    }.getOrNull().toSampleAdbDeviceName()
        ?: packageName.toSampleAdbDeviceName()
        ?: DEFAULT_ADB_DEVICE_NAME

internal fun MainActivity.startRootRuntime() {
    runSessionStart("Starting Root Runtime...") {
        PrivilegeRuntime.create(applicationContext).startRoot()
    }
}

internal fun MainActivity.pairWirelessAdb() {
    val code = screenState.pairingCode.trim()
    val adbDeviceName = currentAdbDeviceNameOverride()
    if (code.isBlank()) {
        screenState = screenState.copy(
            message = "Pairing code is required",
            pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
            pairingMessage = DEFAULT_PAIRING_MESSAGE,
        )
        return
    }

    screenState = screenState.copy(
        pairingStatus = PrivilegeAdbPairingStatus.SEARCHING,
        pairingMessage = "Searching for the pairing service before pairing...",
    )
    runBusy(
        message = "Discovering ADB pairing port and pairing...",
        action = {
            createAdbStarter(adbDeviceName).pair(pairingCode = code)
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

internal fun MainActivity.startNotificationPairing() {
    if (screenState.busy) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        startNotificationPairingAfterPermission = true
        screenState = screenState.copy(
            pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
            pairingMessage = "Allow notifications, then use the pairing notification to enter the code without leaving Settings.",
            message = "Notification permission required",
        )
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE,
        )
        return
    }

    val message = "Notification pairing started. Open Wireless debugging pairing and reply with the code from the notification."
    screenState = screenState.copy(
        pairingStatus = PrivilegeAdbPairingStatus.SEARCHING,
        pairingMessage = message,
        message = message,
    )
    appendLog(message)
    PrivilegeSampleAdbPairingService.start(
        context = this,
        adbDeviceName = currentAdbDeviceNameOverride(),
    )
}

internal fun MainActivity.handleNotificationPairingEvent(intent: Intent) {
    if (intent.action != PrivilegeSampleAdbPairingService.ACTION_PAIRING_EVENT) return

    val event = intent.getStringExtra(PrivilegeSampleAdbPairingService.EXTRA_EVENT) ?: return
    val eventMessage = intent.getStringExtra(PrivilegeSampleAdbPairingService.EXTRA_MESSAGE)
        ?: "Wireless ADB notification pairing event: $event"
    val port = intent.getIntExtra(PrivilegeSampleAdbPairingService.EXTRA_PAIRING_PORT, -1)
        .takeIf { it in 1..65535 }
    val adbDeviceName = intent.getStringExtra(PrivilegeSampleAdbPairingService.EXTRA_ADB_DEVICE_NAME)
    val fingerprint = intent.getStringExtra(PrivilegeSampleAdbPairingService.EXTRA_ADB_KEY_FINGERPRINT)
    val pairingStatus = when (event) {
        PrivilegeSampleAdbPairingService.EVENT_SEARCHING -> PrivilegeAdbPairingStatus.SEARCHING
        PrivilegeSampleAdbPairingService.EVENT_FOUND -> PrivilegeAdbPairingStatus.FOUND
        PrivilegeSampleAdbPairingService.EVENT_PAIRING -> PrivilegeAdbPairingStatus.PAIRING
        PrivilegeSampleAdbPairingService.EVENT_PAIRED -> PrivilegeAdbPairingStatus.PAIRED
        PrivilegeSampleAdbPairingService.EVENT_FAILED -> PrivilegeAdbPairingStatus.FAILED
        PrivilegeSampleAdbPairingService.EVENT_STOPPED -> PrivilegeAdbPairingStatus.NOT_PAIRED
        else -> screenState.pairingStatus
    }

    screenState = screenState.copy(
        pairingStatus = pairingStatus,
        pairingMessage = eventMessage,
        pairingPortText = port?.toString() ?: screenState.pairingPortText,
        adbDeviceName = adbDeviceName ?: screenState.adbDeviceName,
        adbKeyFingerprint = fingerprint ?: screenState.adbKeyFingerprint,
        adbKeyFingerprintLoading = false,
        message = eventMessage,
    )
    appendLog("Notification pairing: $eventMessage")
}

internal fun MainActivity.startWirelessAdb() {
    val adbDeviceName = currentAdbDeviceNameOverride()
    runSessionStart("Discovering ADB connect port and starting Wireless ADB...") {
        PrivilegeRuntime.create(applicationContext).startAdb(
            options = PrivilegeAdbStartOptions(),
            adbDeviceName = adbDeviceName,
        )
    }
}

internal fun MainActivity.switchToTcp() {
    val tcpPort = screenState.tcpPortText.toIntOrNull() ?: PrivilegeAdbStartOptions.DEFAULT_TCP_PORT
    val adbDeviceName = currentAdbDeviceNameOverride()
    runBusy(
        message = "Discovering current ADB connect port and switching to TCP port $tcpPort...",
        action = {
            val starter = createAdbStarter(adbDeviceName)
            val activeConnectPort = starter.discoverConnectPort()
            starter.switchToTcp(currentPort = activeConnectPort, tcpPort = tcpPort)
        },
    ) {
        screenState = screenState.copy(connectPortText = tcpPort.toString())
        "ADB TCP mode requested on port $tcpPort"
    }
}

internal fun MainActivity.restartTcp() {
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

internal fun MainActivity.stopTcp() {
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

internal fun MainActivity.stopServer() {
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

private fun MainActivity.runSessionStart(
    message: String,
    start: () -> PrivilegeSession,
) {
    if (screenState.busy) return
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

private fun <T> MainActivity.runBusy(
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

private fun MainActivity.connectSession(
    session: PrivilegeSession,
    commandLine: String?,
) {
    this.session?.setOnDisconnectedListener(null)
    this.session = session
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

private fun MainActivity.setFailure(throwable: Throwable) {
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

private fun MainActivity.appendLog(line: String) {
    val nextLog = if (screenState.logText.isBlank()) {
        line
    } else {
        screenState.logText + "\n" + line
    }
    screenState = screenState.copy(logText = nextLog.takeLast(MAX_LOG_CHARS))
}

internal fun MainActivity.copyManualShellCommand() {
    val commandLine = screenState.manualShellCommandLine ?: return
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText("Priv Kit manual shell command", commandLine),
    )
    screenState = screenState.copy(message = "Manual shell command copied")
}

internal fun MainActivity.copySessionLog() {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText("Priv Kit wireless ADB log", screenState.wirelessDebugLogText()),
    )
    screenState = screenState.copy(message = "Wireless ADB log copied")
}

private const val MAX_LOG_CHARS = 32_000
internal const val NOTIFICATION_PERMISSION_REQUEST_CODE = 41
private const val SAMPLE_CONFIG_DIRECTORY = ".priv-kit"
private const val ADB_DEVICE_NAME_FILE = "adb-device-name.txt"
private const val DEFAULT_ADB_DEVICE_NAME = "priv-kit"
private const val DEFAULT_PAIRING_MESSAGE =
    "Enter the Wireless debugging pairing code, or reply from the pairing notification."

internal fun String.toPairingCodeDigits(): String =
    filter(Char::isDigit)
        .take(PAIRING_CODE_LENGTH)

private const val PAIRING_CODE_LENGTH = 6
