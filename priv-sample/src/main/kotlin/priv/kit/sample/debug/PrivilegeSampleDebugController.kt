package priv.kit.sample.debug

import androidx.activity.ComponentActivity
import priv.kit.sample.startup.PrivilegeSampleNotificationPairingController
import rikka.shizuku.Shizuku

internal class PrivilegeSampleDebugController(
    override val activity: ComponentActivity,
    override val debugViewModel: PrivilegeSampleDebugViewModel,
) : PrivilegeSampleDebugHost {
    private var initialized = false
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuStatus(append = false)
    }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        handleShizukuBinderDead()
    }
    private val notificationPairingController = PrivilegeSampleNotificationPairingController(
        activity = activity,
        ownerId = { debugViewModel.notificationPairingOwnerId },
        onPairingCodeSubmitted = { pairingCode ->
            screenState = screenState.copy(notificationPairingRunning = false)
            updatePairingCode(pairingCode)
            pairWirelessAdb()
        },
        onStopped = {
            val message = "Notification pairing stopped"
            screenState = screenState.copy(
                notificationPairingRunning = false,
                pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
                pairingMessage = message,
                message = message,
            )
        },
        onUnavailable = { message ->
            screenState = screenState.copy(
                notificationPairingRunning = false,
                pairingMessage = message,
                message = message,
            )
        },
        onDetached = {
            screenState = screenState.copy(notificationPairingRunning = false)
        },
        onPermissionResult = ::handleNotificationPermissionResult,
    )

    fun initialize() {
        if (initialized) return
        initialized = true
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
            initializePrivilegeSample()
            notificationPairingController.startObserving()
        } catch (throwable: Throwable) {
            dispose()
            throw throwable
        }
    }

    fun onHostResumed() {
        if (initialized) {
            handleShizukuHostVisible()
        }
    }

    fun dispose() {
        if (!initialized) return
        initialized = false
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        debugViewModel.closeHostObservers()
        notificationPairingController.dispose()
    }

    override fun requestNotificationPermission() {
        notificationPairingController.requestPermission()
    }

    fun createCallbacks(
        onOpenPrivilegeUi: () -> Unit,
        onBackToHome: () -> Unit,
    ): PrivilegeSampleDebugCallbacks =
        PrivilegeSampleDebugCallbacks(
            openPrivilegeUi = onOpenPrivilegeUi,
            backToHome = onBackToHome,
            destinationSelected = debugViewModel::selectDebugDestination,
            startupTabSelected = debugViewModel::selectStartupTab,
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

    private fun handleNotificationPermissionResult(granted: Boolean) {
        val shouldStartPairing = debugViewModel.startNotificationPairingAfterPermission
        debugViewModel.startNotificationPairingAfterPermission = false
        if (granted && shouldStartPairing) {
            startNotificationPairing()
        } else if (!granted && shouldStartPairing) {
            screenState = screenState.copy(
                notificationPairingRunning = false,
                pairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
                pairingMessage = "Notification permission is required to enter the pairing code from a notification.",
                message = "Notification permission not granted",
            )
        }
    }
}

internal interface PrivilegeSampleDebugHost {
    val activity: ComponentActivity
    val debugViewModel: PrivilegeSampleDebugViewModel

    fun requestNotificationPermission()
}

internal val PrivilegeSampleDebugHost.sampleViewModel: PrivilegeSampleDebugViewModel
    get() = debugViewModel

internal var PrivilegeSampleDebugHost.screenState: PrivilegeSampleScreenState
    get() = debugViewModel.screenState
    set(value) {
        debugViewModel.screenState = value
}

internal val PrivilegeSampleDebugHost.filesDir get() = activity.filesDir
internal val PrivilegeSampleDebugHost.applicationInfo get() = activity.applicationInfo
internal val PrivilegeSampleDebugHost.packageManager get() = activity.packageManager
internal val PrivilegeSampleDebugHost.packageName get() = activity.packageName

internal fun PrivilegeSampleDebugHost.runOnUiThread(action: () -> Unit) {
    activity.runOnUiThread(action)
}

internal fun PrivilegeSampleDebugHost.getSystemService(name: String): Any? =
    activity.getSystemService(name)

internal fun PrivilegeSampleDebugHost.checkSelfPermission(permission: String): Int =
    activity.checkSelfPermission(permission)
