package priv.kit.sample.debug

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.adb.PRIVILEGE_ADB_DEFAULT_TCP_PORT
import priv.kit.core.adb.PrivilegeAdbStartOptions
import priv.kit.core.adb.PrivilegeAdbManager
import priv.kit.core.binder.PrivilegeServerUnavailableException
import priv.kit.sample.startup.PrivilegeSampleShizukuExternalStarter
import priv.kit.sample.startup.SHIZUKU_PERMISSION_REQUEST_CODE
import priv.kit.sample.startup.startPrivilegeSampleNotificationPairing
import priv.kit.sample.startup.stopPrivilegeSampleNotificationPairing
import priv.kit.sample.common.toDiagnosticString
import priv.kit.core.userservice.PrivilegeUserServiceException
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import priv.kit.sample.userservice.IPrivilegeSampleDedicatedUserService
import priv.kit.sample.userservice.IPrivilegeSampleEmbeddedUserService
import priv.kit.sample.userservice.PrivilegeSampleDedicatedUserService
import priv.kit.sample.userservice.PrivilegeSampleEmbeddedUserService
import rikka.shizuku.Shizuku
import java.io.File
import java.nio.charset.StandardCharsets

internal fun PrivilegeSampleDebugHost.initializePrivilegeSample() {
    val adbDeviceNameOverride = loadAdbDeviceNameOverride()
    val manualShellCommandResult = runCatching { sampleViewModel.manualShellCommandLine }
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
    watchServerState()
    refreshShizukuStatus(append = false)
    refreshAdbFingerprint()
}

internal fun PrivilegeSampleDebugHost.updatePairingCode(value: String) {
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

internal fun PrivilegeSampleDebugHost.updateTcpPort(value: String) {
    screenState = screenState.copy(tcpPortText = value)
}

internal fun PrivilegeSampleDebugHost.clearLog() {
    screenState = screenState.copy(logText = "")
}

internal fun PrivilegeSampleDebugHost.watchServerState() {
    sampleViewModel.serverWatcherJob?.cancel()
    sampleViewModel.serverWatcherJob = sampleViewModel.viewModelScope.launch {
        Privilege.serverState.dropWhile { it == null }.collect { serverInfo ->
            if (serverInfo == null) {
                handleServerDisconnected()
            } else {
            connectServer(serverInfo, commandLine = null)
            appendLog(
                "Connected from server handshake: uid=${serverInfo.uid}, " +
                    "pid=${serverInfo.pid}",
            )
            }
        }
    }
}

internal fun PrivilegeSampleDebugHost.updateAdbDeviceName(value: String) {
    saveAdbDeviceNameOverride(value)
    screenState = screenState.copy(
        adbDeviceNameText = value,
        adbDeviceName = value.trim().ifBlank { defaultAdbDeviceName() },
        pairingMessage = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.NOT_PAIRED) {
            "ADB name updated. The persisted ADB key is unchanged."
        } else {
            screenState.pairingMessage
        },
    )
}

internal fun PrivilegeSampleDebugHost.refreshAdbFingerprint() {
    if (screenState.adbKeyFingerprintLoading) return
    val adbDeviceName = currentAdbDeviceNameOverride()
    screenState = screenState.copy(
        adbKeyFingerprintLoading = true,
        pairingMessage = if (screenState.pairingStatus == PrivilegeAdbPairingStatus.NOT_PAIRED) {
            "Loading fingerprint for the persisted ADB identity..."
        } else {
            screenState.pairingMessage
        },
    )
    sampleViewModel.viewModelScope.launch {
        try {
            val info = withContext(Dispatchers.IO) {
                createAdbManager(adbDeviceName).getIdentityInfo()
            }
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
        } catch (throwable: Throwable) {
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

internal fun PrivilegeSampleDebugHost.checkWirelessAdbPairing(showBusy: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
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

    sampleViewModel.viewModelScope.launch {
        try {
            val result = createAdbManager(adbDeviceName).checkPairing()
            val resultMessage = if (result.paired) {
                "Current persisted ADB key is paired on port ${result.port}."
            } else {
                "Current persisted ADB key is not paired" +
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
            appendLog(result.outputText)
        } catch (throwable: Throwable) {
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

private fun PrivilegeSampleDebugHost.currentAdbDeviceNameOverride(): String? =
    screenState.adbDeviceNameText.trim().ifBlank { null }

private fun PrivilegeSampleDebugHost.createAdbManager(adbDeviceName: String?): PrivilegeAdbManager =
    Privilege.createAdbManager(adbDeviceName = adbDeviceName)

private fun PrivilegeSampleDebugHost.loadAdbDeviceNameOverride(): String =
    runCatching {
        val file = adbDeviceNameConfigFile()
        if (file.isFile) file.readText(StandardCharsets.UTF_8).trim() else ""
    }.getOrDefault("")

private fun PrivilegeSampleDebugHost.saveAdbDeviceNameOverride(value: String) {
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

private fun PrivilegeSampleDebugHost.adbDeviceNameConfigFile(): File =
    File(File(filesDir, SAMPLE_CONFIG_DIRECTORY), ADB_DEVICE_NAME_FILE)

private fun PrivilegeSampleDebugHost.defaultAdbDeviceName(): String =
    runCatching {
        applicationInfo.loadLabel(packageManager).toString()
    }.getOrNull().toSampleAdbDeviceName()
        ?: packageName.toSampleAdbDeviceName()
        ?: DEFAULT_ADB_DEVICE_NAME

internal fun PrivilegeSampleDebugHost.startRootRuntime() {
    runServerStart(
        message = "Starting Root Runtime...",
        startupSource = "Root",
    ) {
        Privilege.startRoot()
    }
}

internal fun PrivilegeSampleDebugHost.refreshShizukuStatus(append: Boolean) {
    val readiness = checkShizukuReadiness(requestPermission = false)
    applyShizukuReadiness(readiness)
    if (append) {
        appendLog(readiness.message)
    }
    continuePendingShizukuExternalStart(readiness)
}

internal fun PrivilegeSampleDebugHost.handleShizukuHostVisible() {
    refreshShizukuStatus(append = false)
}

private fun PrivilegeSampleDebugHost.continuePendingShizukuExternalStart(readiness: ShizukuReadiness) {
    if (!sampleViewModel.startShizukuExternalAfterPermission) return
    if (readiness.ready) {
        sampleViewModel.startShizukuExternalAfterPermission = false
        startShizukuExternal()
    } else if (readiness.pendingStartTerminal) {
        sampleViewModel.startShizukuExternalAfterPermission = false
    }
}

internal fun PrivilegeSampleDebugHost.handleShizukuBinderDead() {
    sampleViewModel.startShizukuExternalAfterPermission = false
    sampleViewModel.shizukuExternalStarter?.close()
    sampleViewModel.shizukuExternalStarter = null
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

internal fun PrivilegeSampleDebugHost.startShizukuExternal() {
    if (screenState.busy) return
    val readiness = checkShizukuReadiness(requestPermission = true)
    applyShizukuReadiness(readiness)
    appendLog(readiness.message)
    if (!readiness.ready) return

    val externalStarter = PrivilegeSampleShizukuExternalStarter(activity)
    sampleViewModel.shizukuExternalStarter = externalStarter
    runServerStartRequest(
        message = "Starting through Shizuku...",
        startedMessage = "Shizuku command sent. Waiting for server handshake...",
        startupSource = "Shizuku",
    ) {
        val commandLine = Privilege.createShellStartCommand()
        try {
            externalStarter.start(commandLine)
        } finally {
            externalStarter.close()
            if (sampleViewModel.shizukuExternalStarter === externalStarter) {
                sampleViewModel.shizukuExternalStarter = null
            }
        }
    }
}

private fun PrivilegeSampleDebugHost.checkShizukuReadiness(requestPermission: Boolean): ShizukuReadiness {
    return try {
        if (!Shizuku.pingBinder()) {
            return ShizukuReadiness(
                message = "Shizuku is not running",
                pendingStartTerminal = true,
            )
        }
        if (Shizuku.isPreV11()) {
            return ShizukuReadiness(
                message = "Shizuku pre-v11 is not supported",
                pendingStartTerminal = true,
            )
        }

        val version = Shizuku.getVersion()
        val uid = Shizuku.getUid().takeIf { it >= 0 }
        val minVersion = PrivilegeSampleShizukuExternalStarter.SHIZUKU_USER_SERVICE_MIN_VERSION
        if (version < minVersion) {
            return ShizukuReadiness(
                message = "Shizuku UserService requires API $minVersion, current=$version",
                uid = uid,
                version = version,
                pendingStartTerminal = true,
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
                pendingStartTerminal = true,
            )
        }

        if (requestPermission) {
            sampleViewModel.startShizukuExternalAfterPermission = true
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
            pendingStartTerminal = true,
        )
    }
}

private fun PrivilegeSampleDebugHost.applyShizukuReadiness(readiness: ShizukuReadiness) {
    screenState = screenState.copy(
        shizukuReady = readiness.ready,
        shizukuPermissionGranted = readiness.permissionGranted,
        shizukuUid = readiness.uid,
        shizukuVersion = readiness.version,
        shizukuMessage = readiness.message,
        shizukuLastException = readiness.exceptionText,
        message = if (readiness.ready) "Shizuku ready" else readiness.message,
    )
    if (readiness.exceptionText.isNotBlank()) {
        appendLog(readiness.exceptionText)
    }
}

internal fun PrivilegeSampleDebugHost.pairWirelessAdb() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
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
            createAdbManager(adbDeviceName).pair(pairingCode = code)
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

internal fun PrivilegeSampleDebugHost.startNotificationPairing() {
    if (screenState.busy) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        sampleViewModel.startNotificationPairingAfterPermission = true
        screenState = screenState.copy(
            notificationPairingRunning = false,
            pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
            pairingMessage = "Allow notifications, then use the pairing notification to enter the code without leaving Settings.",
            message = "Notification permission required",
        )
        requestNotificationPermission()
        return
    }

    val message = "Notification pairing started. Open Wireless debugging pairing and reply with the code from the notification."
    screenState = screenState.copy(
        notificationPairingRunning = false,
        pairingStatus = PrivilegeAdbPairingStatus.SEARCHING,
        pairingMessage = message,
        message = message,
    )
    appendLog(message)
    val started = runCatching {
        startPrivilegeSampleNotificationPairing(
            context = activity,
            ownerId = sampleViewModel.notificationPairingOwnerId,
            statusText = message,
        )
    }.getOrElse { throwable ->
        screenState = screenState.copy(
            pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
            pairingMessage = throwable.message ?: throwable.javaClass.name,
            message = throwable.message ?: throwable.javaClass.name,
        )
        false
    }
    screenState = screenState.copy(notificationPairingRunning = started)
    if (!started && screenState.pairingStatus == PrivilegeAdbPairingStatus.SEARCHING) {
        screenState = screenState.copy(
            pairingMessage = "Notification input is unavailable. Use split screen to enter the pairing code.",
            message = "Notification input unavailable",
        )
    }
}

internal fun PrivilegeSampleDebugHost.stopNotificationPairing() {
    val message = "Stopping notification pairing..."
    screenState = screenState.copy(
        notificationPairingRunning = false,
        pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
        pairingMessage = message,
        message = message,
    )
    appendLog(message)
    stopPrivilegeSampleNotificationPairing(activity, sampleViewModel.notificationPairingOwnerId)
}

internal fun PrivilegeSampleDebugHost.startWirelessAdb() {
    val adbDeviceName = currentAdbDeviceNameOverride()
    runServerStart(
        message = "Discovering ADB connect port and starting Wireless ADB...",
        startupSource = "ADB",
    ) {
        Privilege.startAdb(
            options = PrivilegeAdbStartOptions(),
            adbDeviceName = adbDeviceName,
        )
    }
}

internal fun PrivilegeSampleDebugHost.switchToTcp() {
    val tcpPort = screenState.tcpPortText.toIntOrNull() ?: PRIVILEGE_ADB_DEFAULT_TCP_PORT
    val adbDeviceName = currentAdbDeviceNameOverride()
    runBusy(
        message = "Opening or reusing ADB TCP port $tcpPort...",
        action = {
            createAdbManager(adbDeviceName).switchToTcp(tcpPort = tcpPort)
        },
    ) {
        screenState = screenState.copy(connectPortText = tcpPort.toString())
        "ADB TCP mode requested on port $tcpPort"
    }
}

internal fun PrivilegeSampleDebugHost.restartTcp() {
    val tcpPort = screenState.tcpPortText.toIntOrNull() ?: PRIVILEGE_ADB_DEFAULT_TCP_PORT
    val adbDeviceName = currentAdbDeviceNameOverride()
    runServerStart(
        message = "Restarting through ADB TCP port $tcpPort...",
        startupSource = "ADB",
    ) {
        Privilege.startAdb(
            options = PrivilegeAdbStartOptions(
                tcpMode = true,
                tcpPort = tcpPort,
                discoverPort = false,
            ),
            adbDeviceName = adbDeviceName,
        )
    }
}

internal fun PrivilegeSampleDebugHost.stopTcp() {
    val tcpPort = screenState.tcpPortText.toIntOrNull() ?: PRIVILEGE_ADB_DEFAULT_TCP_PORT
    val adbDeviceName = currentAdbDeviceNameOverride()
    runBusy(
        message = "Stopping ADB TCP mode...",
        action = {
            createAdbManager(adbDeviceName).stopTcp(tcpPort = tcpPort)
        },
    ) {
        "ADB TCP mode stop requested"
    }
}

internal fun PrivilegeSampleDebugHost.stopServer() {
    if (screenState.busy) return
    if (!Privilege.pingServer()) {
        screenState = screenState.copy(message = "No server connected")
        appendLog("No server connected")
        return
    }

    screenState = screenState.copy(
        busy = true,
        message = "Stopping Privileged Server...",
    )
    appendLog("Stopping Privileged Server...")

    sampleViewModel.viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { Privilege.shutdownServer() }
            val serviceBinderCached = screenState.systemServiceBinderCached || sampleViewModel.sampleMqsNativeBinder != null
            val userManagerCached = screenState.userManagerCached || sampleViewModel.sampleUserManager != null
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
        } catch (throwable: Throwable) {
            setFailure(throwable)
        }
    }
}

internal fun PrivilegeSampleDebugHost.getUserManagerBinder() {
    runBinderAction("Getting IUserManager...") {
        sampleViewModel.sampleUserManager = PrivilegeSampleUserManager.createFromCurrentProcess()
        BinderActionResult(
            message = "IUserManager cached through current-process Binder + createRemoteBinderWrapper",
            userManagerCached = true,
        )
    }
}

internal fun PrivilegeSampleDebugHost.getUserManagerUsers() {
    val hasCachedUserManager = sampleViewModel.sampleUserManager != null
    runBinderAction(
        message = "Calling IUserManager.getUsers...",
        requireConnected = !hasCachedUserManager,
    ) {
        val userManager = sampleViewModel.sampleUserManager ?: PrivilegeSampleUserManager.createFromCurrentProcess().also {
            sampleViewModel.sampleUserManager = it
        }
        val users = userManager.getUsers()
        BinderActionResult(
            message = users.toBinderMessage(),
            userManagerCached = true,
        )
    }
}

internal fun PrivilegeSampleDebugHost.runImqsNative() {
    val hasCachedRemoteBinder = sampleViewModel.sampleMqsNativeBinder != null
    runBinderAction(
        message = "Probing IMQSNative descriptors...",
        requireConnected = !hasCachedRemoteBinder,
    ) {
        val remoteBinder = sampleViewModel.sampleMqsNativeBinder ?: PrivilegeSampleMqsNative.createRemoteBinder().also {
            sampleViewModel.sampleMqsNativeBinder = it
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

internal fun PrivilegeSampleDebugHost.bindDedicatedUserService() {
    bindSampleUserService(
        label = "dedicated",
        embedded = false,
    )
}

internal fun PrivilegeSampleDebugHost.callDedicatedUserService() {
    callSampleUserService(label = "dedicated")
}

internal fun PrivilegeSampleDebugHost.stopDedicatedUserService() {
    stopSampleUserService(label = "dedicated")
}

internal fun PrivilegeSampleDebugHost.bindEmbeddedUserService() {
    bindSampleUserService(
        label = "embedded",
        embedded = true,
    )
}

internal fun PrivilegeSampleDebugHost.callEmbeddedUserService() {
    callSampleUserService(label = "embedded")
}

internal fun PrivilegeSampleDebugHost.stopEmbeddedUserService() {
    stopSampleUserService(label = "embedded")
}

private fun PrivilegeSampleDebugHost.bindSampleUserService(
    label: String,
    embedded: Boolean,
) {
    runUserServiceAction(
        message = "Binding $label UserService...",
        requireConnected = true,
    ) {
        clearSampleUserService(label)
        val spec = sampleUserServiceSpec(label, embedded)
        Privilege.startUserService(spec)
        val connection = Privilege.bindUserService(spec)
        val serviceMessage = setSampleUserService(label, connection)
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

private fun PrivilegeSampleDebugHost.callSampleUserService(label: String) {
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

private fun PrivilegeSampleDebugHost.stopSampleUserService(label: String) {
    runUserServiceAction(
        message = "Stopping $label UserService...",
        requireConnected = false,
    ) {
        val spec = sampleUserServiceSpec(label, embedded = label == "embedded")
        Privilege.stopUserService(spec)
        clearSampleUserService(label)
        UserServiceActionResult(
            message = "$label UserService stopped",
            dedicatedBound = if (label == "dedicated") false else null,
            embeddedBound = if (label == "embedded") false else null,
            dedicatedCached = if (label == "dedicated") false else null,
            embeddedCached = if (label == "embedded") false else null,
            dedicatedMessage = if (label == "dedicated") "stopped" else null,
            embeddedMessage = if (label == "embedded") "stopped" else null,
        )
    }
}

private fun PrivilegeSampleDebugHost.runUserServiceAction(
    message: String,
    requireConnected: Boolean,
    action: () -> UserServiceActionResult,
) {
    if (screenState.busy) return
    if (requireConnected && !Privilege.pingServer()) {
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

    sampleViewModel.viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) { action() }
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
        } catch (throwable: Throwable) {
            setUserServiceFailure(throwable)
        }
    }
}

private fun PrivilegeSampleDebugHost.setUserServiceFailure(throwable: Throwable) {
    val message = throwable.message ?: throwable.javaClass.name
    val disconnected = throwable is PrivilegeServerUnavailableException
    screenState = screenState.copy(
        busy = false,
        status = if (disconnected) PrivilegeSampleStatus.DISCONNECTED else screenState.status,
        serverInfo = if (disconnected) null else screenState.serverInfo,
        dedicatedUserServiceBound = if (disconnected) false else screenState.dedicatedUserServiceBound,
        embeddedUserServiceBound = if (disconnected) false else screenState.embeddedUserServiceBound,
        dedicatedUserServiceCached = screenState.dedicatedUserServiceCached || sampleViewModel.dedicatedUserServiceConnection != null,
        embeddedUserServiceCached = screenState.embeddedUserServiceCached || sampleViewModel.embeddedUserServiceConnection != null,
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

private fun PrivilegeSampleDebugHost.runBinderAction(
    message: String,
    requireConnected: Boolean = true,
    action: () -> BinderActionResult,
) {
    if (screenState.busy) return
    if (requireConnected && !Privilege.pingServer()) {
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

    sampleViewModel.viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) { action() }
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
        } catch (throwable: Throwable) {
            setBinderFailure(throwable)
        }
    }
}

private fun PrivilegeSampleDebugHost.setBinderFailure(throwable: Throwable) {
    val message = throwable.message ?: throwable.javaClass.name
    val disconnected = throwable is PrivilegeServerUnavailableException
    screenState = screenState.copy(
        busy = false,
        status = if (disconnected) PrivilegeSampleStatus.DISCONNECTED else screenState.status,
        serverInfo = if (disconnected) null else screenState.serverInfo,
        systemServiceBinderCached = screenState.systemServiceBinderCached || sampleViewModel.sampleMqsNativeBinder != null,
        userManagerCached = screenState.userManagerCached || sampleViewModel.sampleUserManager != null,
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
    val pendingStartTerminal: Boolean = false,
)

private fun List<PrivilegeSampleUserInfo>.toBinderMessage(): String =
    buildString {
        append("IUserManager.getUsers returned $size user(s)")
        this@toBinderMessage.forEach { user ->
            appendLine()
            append("user id=${user.id}, name=${user.name.ifBlank { "<unnamed>" }}")
        }
    }

private fun PrivilegeSampleDebugHost.runServerStart(
    message: String,
    startupSource: String?,
    start: suspend () -> PrivilegeServerInfo,
) {
    if (!beginServerStart(message, startupSource)) return

    sampleViewModel.viewModelScope.launch {
        try {
            val serverInfo = start()
            connectServer(serverInfo, commandLine = null)
        } catch (throwable: Throwable) {
            setFailure(throwable)
        }
    }
}

private fun PrivilegeSampleDebugHost.runServerStartRequest(
    message: String,
    startedMessage: String,
    startupSource: String?,
    start: suspend () -> String,
) {
    if (!beginServerStart(message, startupSource)) return

    sampleViewModel.viewModelScope.launch {
        try {
            val output = start()
            screenState = screenState.copy(
                busy = false,
                status = PrivilegeSampleStatus.STARTING,
                message = startedMessage,
            )
            appendLog(output)
            appendLog(startedMessage)
        } catch (throwable: Throwable) {
            setFailure(throwable)
        }
    }
}

private fun PrivilegeSampleDebugHost.beginServerStart(
    message: String,
    startupSource: String?,
): Boolean {
    if (screenState.busy) return false
    screenState = screenState.copy(
        busy = true,
        status = PrivilegeSampleStatus.STARTING,
        serverInfo = null,
        message = message,
    )
    appendStartupSource(startupSource)
    appendLog(message)
    return true
}

private fun PrivilegeSampleDebugHost.appendStartupSource(startupSource: String?) {
    val source = startupSource?.trim()?.takeIf { it.isNotEmpty() } ?: return
    appendLog("Startup source: $source")
}

private fun <T> PrivilegeSampleDebugHost.runBusy(
    message: String,
    action: suspend () -> T,
    onFailure: ((Throwable) -> Unit)? = null,
    onSuccess: (T) -> String,
) {
    if (screenState.busy) return
    screenState = screenState.copy(
        busy = true,
        message = message,
    )
    appendLog(message)

    sampleViewModel.viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) { action() }
            val resultMessage = onSuccess(result)
            screenState = screenState.copy(
                busy = false,
                message = screenState.idleServiceMessage(),
            )
            appendLog(resultMessage)
        } catch (throwable: Throwable) {
            onFailure?.invoke(throwable)
            setFailure(throwable)
        }
    }
}

private fun PrivilegeSampleDebugHost.connectServer(
    serverInfo: PrivilegeServerInfo,
    commandLine: String?,
) {
    val serviceBinderCached = screenState.systemServiceBinderCached || sampleViewModel.sampleMqsNativeBinder != null
    val userManagerCached = screenState.userManagerCached || sampleViewModel.sampleUserManager != null
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
    appendLog("Connected: uid=${serverInfo.uid}, pid=${serverInfo.pid}")
}

private fun PrivilegeSampleDebugHost.handleServerDisconnected() {
    val serviceBinderCached = screenState.systemServiceBinderCached || sampleViewModel.sampleMqsNativeBinder != null
    val userManagerCached = screenState.userManagerCached || sampleViewModel.sampleUserManager != null
    val dedicatedCached = screenState.dedicatedUserServiceCached || sampleViewModel.dedicatedUserServiceConnection != null
    val embeddedCached = screenState.embeddedUserServiceCached || sampleViewModel.embeddedUserServiceConnection != null
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

private fun PrivilegeSampleDebugHost.setFailure(throwable: Throwable) {
    val message = throwable.message ?: throwable.javaClass.name
    screenState = screenState.copy(
        busy = false,
        status = PrivilegeSampleStatus.DISCONNECTED,
        serverInfo = null,
        systemServiceBinderCached = screenState.systemServiceBinderCached || sampleViewModel.sampleMqsNativeBinder != null,
        userManagerCached = screenState.userManagerCached || sampleViewModel.sampleUserManager != null,
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

private fun PrivilegeSampleDebugHost.appendLog(line: String) {
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

private fun sampleUserServiceSpec(
    label: String,
    embedded: Boolean,
): PrivilegeUserServiceSpec =
    PrivilegeUserServiceSpec(
        serviceClassName = sampleUserServiceClassName(label),
        tag = label,
        version = 1,
        embedded = embedded,
    )

private fun sampleUserServiceClassName(label: String): String =
    if (label == "embedded") {
        PrivilegeSampleEmbeddedUserService::class.java.name
    } else {
        PrivilegeSampleDedicatedUserService::class.java.name
    }

private fun PrivilegeSampleDebugHost.sampleUserServiceConnection(label: String): PrivilegeUserServiceConnection {
    val connection = if (label == "embedded") {
        sampleViewModel.embeddedUserServiceConnection
    } else {
        sampleViewModel.dedicatedUserServiceConnection
    }
    return connection ?: throw PrivilegeUserServiceException("$label UserService is not bound")
}

private fun PrivilegeSampleDebugHost.describeSampleUserService(label: String): String {
    val connection = sampleUserServiceConnection(label)
    return if (label == "embedded") {
        val service = IPrivilegeSampleEmbeddedUserService.Stub.asInterface(connection.binder)
            ?: throw PrivilegeUserServiceException("$label UserService returned an invalid Binder")
        service.describe("$label call")
    } else {
        val service = IPrivilegeSampleDedicatedUserService.Stub.asInterface(connection.binder)
            ?: throw PrivilegeUserServiceException("$label UserService returned an invalid Binder")
        service.describe("$label call")
    }
}

private fun PrivilegeSampleDebugHost.setSampleUserService(
    label: String,
    connection: PrivilegeUserServiceConnection,
): String =
    if (label == "embedded") {
        val service = IPrivilegeSampleEmbeddedUserService.Stub.asInterface(connection.binder)
            ?: throw PrivilegeUserServiceException("$label UserService bind returned an invalid Binder")
        val message = service.describe("$label bind")
        sampleViewModel.embeddedUserServiceConnection = connection
        sampleViewModel.embeddedUserService = service
        message
    } else {
        val service = IPrivilegeSampleDedicatedUserService.Stub.asInterface(connection.binder)
            ?: throw PrivilegeUserServiceException("$label UserService bind returned an invalid Binder")
        val message = service.describe("$label bind")
        sampleViewModel.dedicatedUserServiceConnection = connection
        sampleViewModel.dedicatedUserService = service
        message
    }

private fun PrivilegeSampleDebugHost.clearSampleUserService(label: String) {
    if (label == "embedded") {
        sampleViewModel.embeddedUserService = null
        runCatching {
            sampleViewModel.embeddedUserServiceConnection?.close()
        }
        sampleViewModel.embeddedUserServiceConnection = null
    } else {
        sampleViewModel.dedicatedUserService = null
        runCatching {
            sampleViewModel.dedicatedUserServiceConnection?.close()
        }
        sampleViewModel.dedicatedUserServiceConnection = null
    }
}

internal fun PrivilegeSampleDebugHost.copyManualShellCommand() {
    val commandLine = screenState.manualShellCommandLine ?: return
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText("Priv Kit manual shell command", commandLine),
    )
    screenState = screenState.copy(message = "Manual shell command copied")
}

internal fun PrivilegeSampleDebugHost.copySessionLog() {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText("Priv Kit wireless ADB log", screenState.wirelessDebugLogText()),
    )
    screenState = screenState.copy(message = "Wireless ADB log copied")
}

private const val MAX_LOG_CHARS = 32_000
private const val SAMPLE_CONFIG_DIRECTORY = ".priv-kit"
private const val ADB_DEVICE_NAME_FILE = "adb-device-name.txt"
private const val DEFAULT_ADB_DEVICE_NAME = "priv-kit"
private const val DEFAULT_PAIRING_MESSAGE =
    "Enter the Wireless debugging pairing code, or reply from the pairing notification."

internal fun String.toPairingCodeDigits(): String =
    filter(Char::isDigit)
        .take(PAIRING_CODE_LENGTH)

private const val PAIRING_CODE_LENGTH = 6
