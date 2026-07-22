package priv.kit.sample.debug

import android.annotation.SuppressLint
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Job
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.sample.startup.PrivilegeSampleShizukuExternalStarter
import priv.kit.sample.userservice.IPrivilegeSampleDedicatedUserService
import priv.kit.sample.userservice.IPrivilegeSampleEmbeddedUserService
import java.util.UUID

internal class PrivilegeSampleDebugViewModel : ViewModel() {
    var screenState by mutableStateOf(PrivilegeSampleScreenState())
    var selectedDebugDestination by mutableStateOf<PrivilegeSampleDebugDestination>(
        PrivilegeSampleDebugDestination.Connection,
    )
    var selectedStartupTab by mutableStateOf<PrivilegeStartupTab>(PrivilegeStartupTab.Root)
    var serverWatcherJob: Job? = null
    var sampleMqsNativeBinder: IBinder? = null
    var sampleUserManager: PrivilegeSampleUserManagerProxy? = null
    var dedicatedUserServiceConnection: PrivilegeUserServiceConnection? = null
    var embeddedUserServiceConnection: PrivilegeUserServiceConnection? = null
    var dedicatedUserService: IPrivilegeSampleDedicatedUserService? = null
    var embeddedUserService: IPrivilegeSampleEmbeddedUserService? = null
    @Volatile
    var shizukuExternalStarter: PrivilegeSampleShizukuExternalStarter? = null
    var startNotificationPairingAfterPermission = false
    val notificationPairingOwnerId: String = UUID.randomUUID().toString()
    var startShizukuExternalAfterPermission = false
    val manualShellCommandLine: String by lazy(LazyThreadSafetyMode.NONE) {
        Privilege.createShellStartCommand().toSampleHostAdbShellCommand()
    }

    fun selectDebugDestination(destination: PrivilegeSampleDebugDestination) {
        selectedDebugDestination = destination
    }

    fun handlePrivilegeUiConnected(serverInfo: PrivilegeServerInfo) {
        screenState = screenState.copy(
            busy = false,
            status = PrivilegeSampleStatus.CONNECTED,
            serverInfo = serverInfo,
            message = "Connected",
        )
    }

    fun selectStartupTab(tab: PrivilegeStartupTab) {
        selectedStartupTab = tab
    }

    @SuppressLint("EmptySuperCall")
    override fun onCleared() {
        clearRuntimeResources()
        super.onCleared()
    }

    private fun clearRuntimeResources() {
        sampleMqsNativeBinder = null
        sampleUserManager = null
        clearSampleUserServices()
        shizukuExternalStarter?.close()
        shizukuExternalStarter = null
        startShizukuExternalAfterPermission = false
        closeHostObservers()
    }

    fun closeHostObservers() {
        serverWatcherJob?.cancel()
        serverWatcherJob = null
    }

    private fun clearSampleUserServices() {
        embeddedUserService = null
        runCatching {
            embeddedUserServiceConnection?.close()
        }
        embeddedUserServiceConnection = null
        dedicatedUserService = null
        runCatching {
            dedicatedUserServiceConnection?.close()
        }
        dedicatedUserServiceConnection = null
    }
}

private fun String.toSampleHostAdbShellCommand(): String {
    val command = trim()
    return if (command.startsWith(ADB_SHELL_PREFIX)) {
        command
    } else {
        ADB_SHELL_PREFIX + command
    }
}

private const val ADB_SHELL_PREFIX = "adb shell "
