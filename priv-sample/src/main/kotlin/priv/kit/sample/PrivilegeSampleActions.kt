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
import priv.kit.binder.PrivilegeServerDisconnectedException
import priv.kit.core.PrivilegeServerInfo
import priv.kit.runtime.PrivilegeRuntime
import priv.kit.userservice.PrivilegeUserServiceConnection
import priv.kit.userservice.PrivilegeUserServiceNotRunningException
import priv.kit.userservice.PrivilegeUserServiceProcessMode
import priv.kit.userservice.PrivilegeUserServiceSpec
import priv.kit.userservice.PrivilegeUserServiceState
import priv.kit.userservice.PrivilegeUserServiceStatus
import rikka.shizuku.Shizuku
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
    watchServerDisconnected()
    refreshShizukuStatus(append = false)
    refreshAdbFingerprint()
    checkWirelessAdbPairing(showBusy = false)
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
    sampleMqsNativeBinder = null
    sampleUserManager = null
    closeSampleUserServices()
    releaseShizukuDelegate()
    readyServerWatcher?.close()
    readyServerWatcher = null
    serverDisconnectedWatcher?.close()
    serverDisconnectedWatcher = null
    executor.shutdownNow()
}

internal fun MainActivity.watchReadyServers() {
    readyServerWatcher?.close()
    readyServerWatcher = PrivilegeRuntime.watchReadyServers(
        onReady = { serverInfo ->
            runOnUiThread {
                connectServer(serverInfo, commandLine = null)
                appendLog(
                    "Connected from server handshake: uid=${serverInfo.uid}, " +
                        "pid=${serverInfo.pid}, launchMode=${serverInfo.launchMode}",
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

internal fun MainActivity.watchServerDisconnected() {
    serverDisconnectedWatcher?.close()
    serverDisconnectedWatcher = PrivilegeRuntime.addServerDisconnectedListener {
        runOnUiThread {
            handleServerDisconnected()
        }
    }
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
                    message = if (showBusy) screenState.idleServiceMessage() else screenState.message,
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
                    message = if (showBusy) screenState.idleServiceMessage() else screenState.message,
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
    PrivilegeRuntime.createAdbStarter(adbDeviceName = adbDeviceName)

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
    runServerStart("Starting Root Runtime...") {
        PrivilegeRuntime.startRoot()
    }
}

internal fun MainActivity.refreshShizukuStatus(append: Boolean = true) {
    val readiness = checkShizukuReadiness(requestPermission = false)
    applyShizukuReadiness(readiness)
    if (append) {
        appendLog(readiness.message)
    }
}

internal fun MainActivity.handleShizukuBinderDead() {
    startShizukuDelegateAfterPermission = false
    shizukuDelegateExecutor?.close()
    shizukuDelegateExecutor = null
    val message = "Shizuku binder died"
    screenState = screenState.copy(
        shizukuReady = false,
        shizukuPermissionGranted = false,
        shizukuUid = null,
        shizukuVersion = null,
        shizukuMessage = message,
        message = message,
    )
    appendLog(message)
}

internal fun MainActivity.handleShizukuPermissionResult(
    requestCode: Int,
    grantResult: Int,
) {
    if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return
    val granted = grantResult == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        startShizukuDelegateAfterPermission = false
        val message = "Shizuku permission denied"
        screenState = screenState.copy(
            shizukuReady = false,
            shizukuPermissionGranted = false,
            shizukuMessage = message,
            message = message,
        )
        appendLog(message)
        return
    }

    val shouldStart = startShizukuDelegateAfterPermission
    startShizukuDelegateAfterPermission = false
    refreshShizukuStatus()
    if (shouldStart) {
        startShizukuDelegate()
    }
}

internal fun MainActivity.startShizukuDelegate() {
    if (screenState.busy) return
    val readiness = checkShizukuReadiness(requestPermission = true)
    applyShizukuReadiness(readiness)
    appendLog(readiness.message)
    if (!readiness.ready) return

    val delegateExecutor = PrivilegeSampleShizukuDelegateExecutor(this)
    shizukuDelegateExecutor = delegateExecutor
    runServerStart("Starting through Shizuku Delegate...") {
        try {
            PrivilegeRuntime.startDelegate(delegateExecutor)
        } finally {
            delegateExecutor.close()
            if (shizukuDelegateExecutor === delegateExecutor) {
                shizukuDelegateExecutor = null
            }
        }
    }
}

private fun MainActivity.checkShizukuReadiness(requestPermission: Boolean): ShizukuReadiness =
    try {
        if (!Shizuku.pingBinder()) {
            return ShizukuReadiness(message = "Shizuku is not running")
        }
        if (Shizuku.isPreV11()) {
            return ShizukuReadiness(message = "Shizuku pre-v11 is not supported")
        }

        val version = Shizuku.getVersion()
        val uid = Shizuku.getUid().takeIf { it >= 0 }
        if (version < SHIZUKU_USER_SERVICE_MIN_VERSION) {
            return ShizukuReadiness(
                message = "Shizuku UserService requires API $SHIZUKU_USER_SERVICE_MIN_VERSION, current=$version",
                uid = uid,
                version = version,
            )
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return ShizukuReadiness(
                ready = true,
                permissionGranted = true,
                uid = uid,
                version = version,
                message = "Shizuku ready: uid=${uid ?: "-"}, version=$version",
            )
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return ShizukuReadiness(
                uid = uid,
                version = version,
                message = "Shizuku permission denied permanently",
            )
        }

        if (requestPermission) {
            startShizukuDelegateAfterPermission = true
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            return ShizukuReadiness(
                uid = uid,
                version = version,
                message = "Shizuku permission requested",
            )
        }

        ShizukuReadiness(
            uid = uid,
            version = version,
            message = "Shizuku permission required",
        )
    } catch (throwable: Throwable) {
        ShizukuReadiness(
            message = "Shizuku error: ${throwable.message ?: throwable.javaClass.name}",
            exceptionText = throwable.toDiagnosticString(),
        )
    }

private fun MainActivity.applyShizukuReadiness(readiness: ShizukuReadiness) {
    screenState = screenState.copy(
        shizukuReady = readiness.ready,
        shizukuPermissionGranted = readiness.permissionGranted,
        shizukuUid = readiness.uid,
        shizukuVersion = readiness.version,
        shizukuMessage = readiness.message,
        shizukuLastException = readiness.exceptionText,
        message = readiness.toGlobalMessage(),
    )
    if (readiness.exceptionText.isNotBlank()) {
        appendLog(readiness.exceptionText)
    }
}

private fun MainActivity.releaseShizukuDelegate() {
    shizukuDelegateExecutor?.close()
    shizukuDelegateExecutor = null
    startShizukuDelegateAfterPermission = false
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

internal fun MainActivity.stopNotificationPairing() {
    val message = "Stopping notification pairing..."
    screenState = screenState.copy(
        pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
        pairingMessage = message,
        message = message,
    )
    appendLog(message)
    PrivilegeSampleAdbPairingService.stop(this)
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

    val globalMessage = if (
        event == PrivilegeSampleAdbPairingService.EVENT_SEARCHING ||
        event == PrivilegeSampleAdbPairingService.EVENT_FOUND ||
        event == PrivilegeSampleAdbPairingService.EVENT_PAIRING
    ) {
        eventMessage
    } else {
        screenState.idleServiceMessage()
    }
    screenState = screenState.copy(
        pairingStatus = pairingStatus,
        pairingMessage = eventMessage,
        pairingPortText = port?.toString() ?: screenState.pairingPortText,
        adbDeviceName = adbDeviceName ?: screenState.adbDeviceName,
        adbKeyFingerprint = fingerprint ?: screenState.adbKeyFingerprint,
        adbKeyFingerprintLoading = false,
        message = globalMessage,
    )
    appendLog("Notification pairing: $eventMessage")
}

internal fun MainActivity.startWirelessAdb() {
    val adbDeviceName = currentAdbDeviceNameOverride()
    runServerStart("Discovering ADB connect port and starting Wireless ADB...") {
        PrivilegeRuntime.startAdb(
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
    runServerStart("Restarting through ADB TCP port $tcpPort...") {
        PrivilegeRuntime.startAdb(
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
    if (!PrivilegeRuntime.pingServer()) {
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
            PrivilegeRuntime.shutdownServer()
            runOnUiThread {
                val serviceBinderCached = screenState.systemServiceBinderCached || sampleMqsNativeBinder != null
                val userManagerCached = screenState.userManagerCached || sampleUserManager != null
                screenState = screenState.copy(
                    busy = false,
                    status = PrivilegeSampleStatus.DISCONNECTED,
                    serverInfo = null,
                    systemServiceBinderCached = serviceBinderCached,
                    userManagerCached = userManagerCached,
                    binderMessage = stoppedBinderMessage(serviceBinderCached, userManagerCached),
                    binderLastException = "",
                    message = "Ready",
                )
                appendLog("Server stopped")
            }
        } catch (throwable: Throwable) {
            runOnUiThread {
                setFailure(throwable)
            }
        }
    }
}

internal fun MainActivity.getUserManagerBinder() {
    runBinderAction("Getting IUserManager...") {
        sampleUserManager = PrivilegeSampleUserManager.createFromCurrentProcess()
        BinderActionResult(
            message = "IUserManager cached through current-process Binder + createRemoteBinderWrapper",
            userManagerCached = true,
        )
    }
}

internal fun MainActivity.getUserManagerUsers() {
    val hasCachedUserManager = sampleUserManager != null
    runBinderAction(
        message = "Calling IUserManager.getUsers...",
        requireConnected = !hasCachedUserManager,
    ) {
        val userManager = sampleUserManager ?: PrivilegeSampleUserManager.createFromCurrentProcess().also {
            sampleUserManager = it
        }
        val users = userManager.getUsers()
        BinderActionResult(
            message = users.toBinderMessage(),
            userManagerCached = true,
        )
    }
}

internal fun MainActivity.runImqsNative() {
    val hasCachedRemoteBinder = sampleMqsNativeBinder != null
    runBinderAction(
        message = "Probing IMQSNative descriptors...",
        requireConnected = !hasCachedRemoteBinder,
    ) {
        val remoteBinder = sampleMqsNativeBinder ?: PrivilegeSampleMqsNative.createRemoteBinder().also {
            sampleMqsNativeBinder = it
        }
        val result = PrivilegeSampleMqsNative.probeDescriptor(remoteBinder)
        BinderActionResult(
            message = buildString {
                append("IMQSNative descriptor probe")
                appendLine()
                append("local=${result.localDescriptor ?: result.localError ?: "<unknown>"}")
                appendLine()
                append("remote=${result.remoteDescriptor ?: result.remoteError ?: "<unknown>"}")
            },
            systemServiceBinderCached = true,
            mqsNativeProbeUpdated = true,
            mqsNativeLocalDescriptor = result.localDescriptor,
            mqsNativeLocalError = result.localError,
            mqsNativeRemoteDescriptor = result.remoteDescriptor,
            mqsNativeRemoteError = result.remoteError,
        )
    }
}

internal fun MainActivity.bindDedicatedUserService() {
    bindSampleUserService(
        label = "dedicated",
        processMode = PrivilegeUserServiceProcessMode.DEDICATED_PROCESS,
    )
}

internal fun MainActivity.callDedicatedUserService() {
    callSampleUserService(label = "dedicated")
}

internal fun MainActivity.stopDedicatedUserService() {
    stopSampleUserService(label = "dedicated")
}

internal fun MainActivity.bindEmbeddedUserService() {
    bindSampleUserService(
        label = "embedded",
        processMode = PrivilegeUserServiceProcessMode.IN_SERVER_PROCESS,
    )
}

internal fun MainActivity.callEmbeddedUserService() {
    callSampleUserService(label = "embedded")
}

internal fun MainActivity.stopEmbeddedUserService() {
    stopSampleUserService(label = "embedded")
}

private fun MainActivity.bindSampleUserService(
    label: String,
    processMode: PrivilegeUserServiceProcessMode,
) {
    runUserServiceAction(
        message = "Binding $label UserService...",
        requireConnected = true,
    ) {
        clearSampleUserService(label)
        val spec = sampleUserServiceSpec(label, processMode)
        PrivilegeRuntime.startUserService(spec)
        val connection = PrivilegeRuntime.bindUserService(spec)
        val serviceMessage = setSampleUserService(label, connection)
        watchSampleUserServiceStatus(label, spec)
        UserServiceActionResult(
            message = "$label UserService bound",
            dedicatedBound = if (label == "dedicated") true else null,
            embeddedBound = if (label == "embedded") true else null,
            dedicatedCached = if (label == "dedicated") true else null,
            embeddedCached = if (label == "embedded") true else null,
            dedicatedMessage = if (label == "dedicated") serviceMessage else null,
            embeddedMessage = if (label == "embedded") serviceMessage else null,
        )
    }
}

private fun MainActivity.callSampleUserService(label: String) {
    runUserServiceAction(
        message = "Calling $label UserService...",
        requireConnected = false,
    ) {
        val serviceMessage = describeSampleUserService(label)
        UserServiceActionResult(
            message = "$label UserService call returned",
            dedicatedMessage = if (label == "dedicated") serviceMessage else null,
            embeddedMessage = if (label == "embedded") serviceMessage else null,
        )
    }
}

private fun MainActivity.stopSampleUserService(label: String) {
    runUserServiceAction(
        message = "Stopping $label UserService...",
        requireConnected = false,
    ) {
        val spec = sampleUserServiceSpec(label, sampleUserServiceProcessMode(label))
        val status = PrivilegeRuntime.stopUserService(spec)
        clearSampleUserService(label)
        UserServiceActionResult(
            message = "$label UserService stopped",
            dedicatedBound = if (label == "dedicated") false else null,
            embeddedBound = if (label == "embedded") false else null,
            dedicatedCached = if (label == "dedicated") false else null,
            embeddedCached = if (label == "embedded") false else null,
            dedicatedMessage = if (label == "dedicated") "state=${status.state}, bound=${status.boundCount}" else null,
            embeddedMessage = if (label == "embedded") "state=${status.state}, bound=${status.boundCount}" else null,
        )
    }
}

private fun MainActivity.runUserServiceAction(
    message: String,
    requireConnected: Boolean,
    action: () -> UserServiceActionResult,
) {
    if (screenState.busy) return
    if (requireConnected && !PrivilegeRuntime.pingServer()) {
        screenState = screenState.copy(
            userServiceMessage = "No server connected",
            userServiceLastException = "",
            message = "No server connected",
        )
        appendLog("No server connected")
        return
    }

    screenState = screenState.copy(
        busy = true,
        userServiceMessage = message,
        userServiceLastException = "",
        message = message,
    )
    appendLog(message)

    executor.execute {
        try {
            val result = action()
            runOnUiThread {
                screenState = screenState.copy(
                    busy = false,
                    dedicatedUserServiceBound = result.dedicatedBound ?: screenState.dedicatedUserServiceBound,
                    embeddedUserServiceBound = result.embeddedBound ?: screenState.embeddedUserServiceBound,
                    dedicatedUserServiceCached = result.dedicatedCached ?: screenState.dedicatedUserServiceCached,
                    embeddedUserServiceCached = result.embeddedCached ?: screenState.embeddedUserServiceCached,
                    dedicatedUserServiceMessage = result.dedicatedMessage ?: screenState.dedicatedUserServiceMessage,
                    embeddedUserServiceMessage = result.embeddedMessage ?: screenState.embeddedUserServiceMessage,
                    userServiceMessage = result.message,
                    userServiceLastException = result.exceptionText,
                    message = screenState.idleServiceMessage(),
                )
                appendLog(result.message)
                result.dedicatedMessage?.let { appendLog(it) }
                result.embeddedMessage?.let { appendLog(it) }
            }
        } catch (throwable: Throwable) {
            runOnUiThread {
                setUserServiceFailure(throwable)
            }
        }
    }
}

private fun MainActivity.setUserServiceFailure(throwable: Throwable) {
    val message = throwable.message ?: throwable.javaClass.name
    val disconnected = throwable is PrivilegeServerDisconnectedException
    if (disconnected) {
        closeSampleUserServiceStatusWatchers()
    }
    screenState = screenState.copy(
        busy = false,
        status = if (disconnected) PrivilegeSampleStatus.DISCONNECTED else screenState.status,
        serverInfo = if (disconnected) null else screenState.serverInfo,
        dedicatedUserServiceBound = if (disconnected) false else screenState.dedicatedUserServiceBound,
        embeddedUserServiceBound = if (disconnected) false else screenState.embeddedUserServiceBound,
        dedicatedUserServiceCached = screenState.dedicatedUserServiceCached || dedicatedUserServiceConnection != null,
        embeddedUserServiceCached = screenState.embeddedUserServiceCached || embeddedUserServiceConnection != null,
        userServiceMessage = message,
        userServiceLastException = throwable.toDiagnosticString(),
        message = message,
    )
    appendLog("UserService error: $message")
    appendLog(throwable.toDiagnosticString())
}

private data class UserServiceActionResult(
    val message: String,
    val dedicatedBound: Boolean? = null,
    val embeddedBound: Boolean? = null,
    val dedicatedCached: Boolean? = null,
    val embeddedCached: Boolean? = null,
    val dedicatedMessage: String? = null,
    val embeddedMessage: String? = null,
    val exceptionText: String = "",
)

private fun MainActivity.runBinderAction(
    message: String,
    requireConnected: Boolean = true,
    action: () -> BinderActionResult,
) {
    if (screenState.busy) return
    if (requireConnected && !PrivilegeRuntime.pingServer()) {
        screenState = screenState.copy(
            binderMessage = "No server connected",
            binderLastException = "",
            message = "No server connected",
        )
        appendLog("No server connected")
        return
    }

    screenState = screenState.copy(
        busy = true,
        binderMessage = message,
        binderLastException = "",
        message = message,
    )
    appendLog(message)

    executor.execute {
        try {
            val result = action()
            runOnUiThread {
                screenState = screenState.copy(
                    busy = false,
                    systemServiceBinderCached = result.systemServiceBinderCached
                        ?: screenState.systemServiceBinderCached,
                    userManagerCached = result.userManagerCached ?: screenState.userManagerCached,
                    mqsNativeLocalDescriptor = if (result.mqsNativeProbeUpdated) {
                        result.mqsNativeLocalDescriptor
                    } else {
                        screenState.mqsNativeLocalDescriptor
                    },
                    mqsNativeLocalError = if (result.mqsNativeProbeUpdated) {
                        result.mqsNativeLocalError
                    } else {
                        screenState.mqsNativeLocalError
                    },
                    mqsNativeRemoteDescriptor = if (result.mqsNativeProbeUpdated) {
                        result.mqsNativeRemoteDescriptor
                    } else {
                        screenState.mqsNativeRemoteDescriptor
                    },
                    mqsNativeRemoteError = if (result.mqsNativeProbeUpdated) {
                        result.mqsNativeRemoteError
                    } else {
                        screenState.mqsNativeRemoteError
                    },
                    binderMessage = result.message,
                    binderLastException = result.exceptionText,
                    message = screenState.idleServiceMessage(),
                )
                appendLog(result.message)
            }
        } catch (throwable: Throwable) {
            runOnUiThread {
                setBinderFailure(throwable)
            }
        }
    }
}

private fun MainActivity.setBinderFailure(throwable: Throwable) {
    val message = throwable.message ?: throwable.javaClass.name
    val disconnected = throwable is PrivilegeServerDisconnectedException
    screenState = screenState.copy(
        busy = false,
        status = if (disconnected) PrivilegeSampleStatus.DISCONNECTED else screenState.status,
        serverInfo = if (disconnected) null else screenState.serverInfo,
        systemServiceBinderCached = screenState.systemServiceBinderCached || sampleMqsNativeBinder != null,
        userManagerCached = screenState.userManagerCached || sampleUserManager != null,
        binderMessage = message,
        binderLastException = throwable.toDiagnosticString(),
        message = message,
    )
    appendLog("Binder error: $message")
    appendLog(throwable.toDiagnosticString())
}

private data class BinderActionResult(
    val message: String,
    val systemServiceBinderCached: Boolean? = null,
    val userManagerCached: Boolean? = null,
    val mqsNativeProbeUpdated: Boolean = false,
    val mqsNativeLocalDescriptor: String? = null,
    val mqsNativeLocalError: String? = null,
    val mqsNativeRemoteDescriptor: String? = null,
    val mqsNativeRemoteError: String? = null,
    val exceptionText: String = "",
)

private data class ShizukuReadiness(
    val ready: Boolean = false,
    val permissionGranted: Boolean = false,
    val uid: Int? = null,
    val version: Int? = null,
    val message: String,
    val exceptionText: String = "",
)

private fun ShizukuReadiness.toGlobalMessage(): String =
    if (ready) "Shizuku ready" else message

private fun List<PrivilegeSampleUserInfo>.toBinderMessage(): String =
    buildString {
        append("IUserManager.getUsers returned ${size} user(s)")
        this@toBinderMessage.forEach { user ->
            appendLine()
            append("user id=${user.id}, name=${user.name.ifBlank { "<unnamed>" }}")
        }
    }

private fun MainActivity.runServerStart(
    message: String,
    start: () -> PrivilegeServerInfo,
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
            val serverInfo = start()
            runOnUiThread {
                connectServer(serverInfo, commandLine = null)
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
                    message = screenState.idleServiceMessage(),
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

private fun MainActivity.connectServer(
    serverInfo: PrivilegeServerInfo,
    commandLine: String?,
) {
    val serviceBinderCached = screenState.systemServiceBinderCached || sampleMqsNativeBinder != null
    val userManagerCached = screenState.userManagerCached || sampleUserManager != null
    screenState = screenState.copy(
        busy = false,
        status = PrivilegeSampleStatus.CONNECTED,
        serverInfo = serverInfo,
        manualShellCommandLine = commandLine ?: screenState.manualShellCommandLine,
        systemServiceBinderCached = serviceBinderCached,
        userManagerCached = userManagerCached,
        binderMessage = connectedBinderMessage(serviceBinderCached, userManagerCached),
        binderLastException = "",
        message = "Connected",
    )
    appendLog("Connected: uid=${serverInfo.uid}, pid=${serverInfo.pid}, launchMode=${serverInfo.launchMode}")
}

private fun MainActivity.handleServerDisconnected() {
    val serviceBinderCached = screenState.systemServiceBinderCached || sampleMqsNativeBinder != null
    val userManagerCached = screenState.userManagerCached || sampleUserManager != null
    closeSampleUserServiceStatusWatchers()
    val dedicatedCached = screenState.dedicatedUserServiceCached || dedicatedUserServiceConnection != null
    val embeddedCached = screenState.embeddedUserServiceCached || embeddedUserServiceConnection != null
    screenState = screenState.copy(
        busy = false,
        status = PrivilegeSampleStatus.DISCONNECTED,
        serverInfo = null,
        systemServiceBinderCached = serviceBinderCached,
        userManagerCached = userManagerCached,
        dedicatedUserServiceBound = false,
        embeddedUserServiceBound = false,
        dedicatedUserServiceCached = dedicatedCached,
        embeddedUserServiceCached = embeddedCached,
        binderMessage = disconnectedBinderMessage(serviceBinderCached, userManagerCached),
        userServiceMessage = if (dedicatedCached || embeddedCached) {
            "Server disconnected; cached UserService references remain clickable for the expected error test"
        } else {
            screenState.userServiceMessage
        },
        binderLastException = "",
        message = "Binder died",
    )
    appendLog("Binder died")
}

private fun MainActivity.setFailure(throwable: Throwable) {
    val message = throwable.message ?: throwable.javaClass.name
    screenState = screenState.copy(
        busy = false,
        status = PrivilegeSampleStatus.DISCONNECTED,
        serverInfo = null,
        systemServiceBinderCached = screenState.systemServiceBinderCached || sampleMqsNativeBinder != null,
        userManagerCached = screenState.userManagerCached || sampleUserManager != null,
        binderMessage = "Connection failed",
        binderLastException = throwable.toDiagnosticString(),
        message = message,
    )
    appendLog("Error: $message")
    appendLog(throwable.toDiagnosticString())
}

private fun connectedBinderMessage(
    serviceBinderCached: Boolean,
    userManagerCached: Boolean,
): String =
    when {
        userManagerCached -> "Connected. Cached IUserManager is ready."
        serviceBinderCached -> "Connected. Cached IMQSNative remote Binder is ready."
        else -> "Connected. Get IUserManager or run IMQSNative to test Binder transact."
    }

private fun stoppedBinderMessage(
    serviceBinderCached: Boolean,
    userManagerCached: Boolean,
): String =
    when {
        userManagerCached -> "Server stopped; cached IUserManager remains"
        serviceBinderCached -> "Server stopped; cached IMQSNative remote Binder remains"
        else -> "Server stopped"
    }

private fun disconnectedBinderMessage(
    serviceBinderCached: Boolean,
    userManagerCached: Boolean,
): String =
    when {
        userManagerCached -> "Server disconnected; cached IUserManager remains clickable for the expected error test"
        serviceBinderCached -> "Server disconnected; cached IMQSNative Binder remains clickable for the expected error test"
        else -> "Server disconnected"
    }

private fun MainActivity.appendLog(line: String) {
    val nextLog = if (screenState.logText.isBlank()) {
        line
    } else {
        screenState.logText + "\n" + line
    }
    screenState = screenState.copy(logText = nextLog.takeLast(MAX_LOG_CHARS))
}

private fun PrivilegeSampleScreenState.idleServiceMessage(): String =
    when (status) {
        PrivilegeSampleStatus.CONNECTED -> "Connected"
        PrivilegeSampleStatus.DISCONNECTED -> "Ready"
        PrivilegeSampleStatus.STARTING -> message
    }

private fun MainActivity.sampleUserServiceSpec(
    label: String,
    processMode: PrivilegeUserServiceProcessMode,
): PrivilegeUserServiceSpec =
    PrivilegeUserServiceSpec(
        serviceClassName = sampleUserServiceClassName(label),
        tag = label,
        version = 1,
        processMode = processMode,
    )

private fun MainActivity.sampleUserServiceProcessMode(label: String): PrivilegeUserServiceProcessMode =
    if (label == "embedded") {
        PrivilegeUserServiceProcessMode.IN_SERVER_PROCESS
    } else {
        PrivilegeUserServiceProcessMode.DEDICATED_PROCESS
    }

private fun sampleUserServiceClassName(label: String): String =
    if (label == "embedded") {
        PrivilegeSampleEmbeddedUserService::class.java.name
    } else {
        PrivilegeSampleDedicatedUserService::class.java.name
    }

private fun MainActivity.watchSampleUserServiceStatus(
    label: String,
    spec: PrivilegeUserServiceSpec,
) {
    closeSampleUserServiceStatusWatcher(label)
    val watcher = PrivilegeRuntime.watchUserServiceStatus(
        spec = spec,
        onStatus = { status ->
            runOnUiThread {
                applySampleUserServiceStatus(label, status)
            }
        },
        onFailure = { throwable ->
            if (throwable !is PrivilegeServerDisconnectedException) {
                runOnUiThread {
                    appendLog("UserService status error: ${throwable.message ?: throwable.javaClass.name}")
                    appendLog(throwable.toDiagnosticString())
                }
            }
        },
    )
    if (label == "embedded") {
        embeddedUserServiceStatusWatcher = watcher
    } else {
        dedicatedUserServiceStatusWatcher = watcher
    }
}

private fun MainActivity.applySampleUserServiceStatus(
    label: String,
    status: PrivilegeUserServiceStatus,
) {
    val bound = status.state == PrivilegeUserServiceState.RUNNING && status.boundCount > 0
    val statusMessage = status.toSampleUserServiceMessage()
    if (!bound) {
        closeSampleUserServiceStatusWatcher(label)
        appendLog("$label UserService status: $statusMessage")
    }
    screenState = if (label == "embedded") {
        screenState.copy(
            embeddedUserServiceBound = bound,
            embeddedUserServiceCached = screenState.embeddedUserServiceCached || embeddedUserServiceConnection != null,
            embeddedUserServiceMessage = statusMessage,
            userServiceMessage = if (bound) screenState.userServiceMessage else "$label UserService $statusMessage",
            message = if (bound) screenState.message else screenState.idleServiceMessage(),
        )
    } else {
        screenState.copy(
            dedicatedUserServiceBound = bound,
            dedicatedUserServiceCached = screenState.dedicatedUserServiceCached || dedicatedUserServiceConnection != null,
            dedicatedUserServiceMessage = statusMessage,
            userServiceMessage = if (bound) screenState.userServiceMessage else "$label UserService $statusMessage",
            message = if (bound) screenState.message else screenState.idleServiceMessage(),
        )
    }
}

private fun MainActivity.closeSampleUserServiceStatusWatcher(label: String) {
    if (label == "embedded") {
        embeddedUserServiceStatusWatcher?.close()
        embeddedUserServiceStatusWatcher = null
    } else {
        dedicatedUserServiceStatusWatcher?.close()
        dedicatedUserServiceStatusWatcher = null
    }
}

private fun MainActivity.closeSampleUserServiceStatusWatchers() {
    closeSampleUserServiceStatusWatcher("dedicated")
    closeSampleUserServiceStatusWatcher("embedded")
}

private fun MainActivity.sampleUserServiceConnection(label: String): PrivilegeUserServiceConnection {
    val connection = if (label == "embedded") {
        embeddedUserServiceConnection
    } else {
        dedicatedUserServiceConnection
    }
    return connection ?: throw PrivilegeUserServiceNotRunningException("$label UserService is not bound")
}

private fun PrivilegeUserServiceStatus.toSampleUserServiceMessage(): String =
    buildString {
        append("state=")
        append(state)
        append(", bound=")
        append(boundCount)
        if (pid > 0) {
            append(", pid=")
            append(pid)
        }
        lastError?.let { error ->
            append(", error=")
            append(error)
        }
    }

private fun MainActivity.describeSampleUserService(label: String): String {
    val connection = sampleUserServiceConnection(label)
    return connection.call("$label UserService call") { binder ->
        if (label == "embedded") {
            val service = IPrivilegeSampleEmbeddedUserService.Stub.asInterface(binder)
                ?: throw PrivilegeUserServiceNotRunningException("$label UserService returned an invalid Binder")
            service.describe("$label call")
        } else {
            val service = IPrivilegeSampleDedicatedUserService.Stub.asInterface(binder)
                ?: throw PrivilegeUserServiceNotRunningException("$label UserService returned an invalid Binder")
            service.describe("$label call")
        }
    }
}

private fun MainActivity.setSampleUserService(
    label: String,
    connection: PrivilegeUserServiceConnection,
): String =
    if (label == "embedded") {
        val service = connection.requireInterface("$label UserService bind") {
            IPrivilegeSampleEmbeddedUserService.Stub.asInterface(it)
        }
        val message = connection.call("$label UserService bind") {
            service.describe("$label bind")
        }
        embeddedUserServiceConnection = connection
        embeddedUserService = service
        message
    } else {
        val service = connection.requireInterface("$label UserService bind") {
            IPrivilegeSampleDedicatedUserService.Stub.asInterface(it)
        }
        val message = connection.call("$label UserService bind") {
            service.describe("$label bind")
        }
        dedicatedUserServiceConnection = connection
        dedicatedUserService = service
        message
    }

private fun MainActivity.clearSampleUserService(label: String) {
    if (label == "embedded") {
        closeSampleUserServiceStatusWatcher(label)
        embeddedUserService = null
        runCatching {
            embeddedUserServiceConnection?.close()
        }
        embeddedUserServiceConnection = null
    } else {
        closeSampleUserServiceStatusWatcher(label)
        dedicatedUserService = null
        runCatching {
            dedicatedUserServiceConnection?.close()
        }
        dedicatedUserServiceConnection = null
    }
}

private fun MainActivity.closeSampleUserServices() {
    clearSampleUserService("dedicated")
    clearSampleUserService("embedded")
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
internal const val SHIZUKU_PERMISSION_REQUEST_CODE = 42
internal const val SHIZUKU_USER_SERVICE_MIN_VERSION = 10
private const val SAMPLE_CONFIG_DIRECTORY = ".priv-kit"
private const val ADB_DEVICE_NAME_FILE = "adb-device-name.txt"
private const val DEFAULT_ADB_DEVICE_NAME = "priv-kit"
private const val DEFAULT_PAIRING_MESSAGE =
    "Enter the Wireless debugging pairing code, or reply from the pairing notification."

internal fun String.toPairingCodeDigits(): String =
    filter(Char::isDigit)
        .take(PAIRING_CODE_LENGTH)

private const val PAIRING_CODE_LENGTH = 6
