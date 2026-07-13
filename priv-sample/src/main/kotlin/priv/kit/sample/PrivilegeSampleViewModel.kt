package priv.kit.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import android.os.IBinder
import priv.kit.Privilege
import priv.kit.PrivilegeUserServiceConnection
import java.io.Closeable
import java.util.concurrent.Executors

internal class PrivilegeSampleViewModel : ViewModel() {
    var screenState by mutableStateOf(PrivilegeSampleScreenState())
    var selectedStartupTab by mutableStateOf<PrivilegeStartupTab>(PrivilegeStartupTab.Root)
    val executor = Executors.newSingleThreadExecutor()
    var serverConnectedListener: Closeable? = null
    var serverDisconnectedWatcher: Closeable? = null
    var sampleMqsNativeBinder: IBinder? = null
    var sampleUserManager: PrivilegeSampleUserManagerProxy? = null
    var dedicatedUserServiceConnection: PrivilegeUserServiceConnection? = null
    var embeddedUserServiceConnection: PrivilegeUserServiceConnection? = null
    var dedicatedUserService: IPrivilegeSampleDedicatedUserService? = null
    var embeddedUserService: IPrivilegeSampleEmbeddedUserService? = null
    @Volatile
    var shizukuExternalStarter: PrivilegeSampleShizukuExternalStarter? = null
    var startNotificationPairingAfterPermission = false
    var startShizukuExternalAfterPermission = false
    val manualShellCommandLine: String by lazy(LazyThreadSafetyMode.NONE) {
        Privilege.createShellStartCommand().toSampleHostAdbShellCommand()
    }

    val backStack = mutableStateListOf<PrivilegeSampleDestination>(
        PrivilegeSampleDestination.Connection,
    )

    fun selectDestination(destination: PrivilegeSampleDestination) {
        if (destination == PrivilegeSampleDestination.PrivilegeUi) {
            openPrivilegeUi()
            return
        }
        if (backStack.lastOrNull() == destination) return
        backStack.clear()
        backStack += destination
    }

    fun openPrivilegeUi() {
        if (backStack.lastOrNull() == PrivilegeSampleDestination.PrivilegeUi) return
        backStack += PrivilegeSampleDestination.PrivilegeUi
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun selectStartupTab(tab: PrivilegeStartupTab) {
        selectedStartupTab = tab
    }

    override fun onCleared() {
        clearRuntimeResources()
        executor.shutdownNow()
        super.onCleared()
    }

    private fun clearRuntimeResources() {
        sampleMqsNativeBinder = null
        sampleUserManager = null
        clearSampleUserServices()
        shizukuExternalStarter?.close()
        shizukuExternalStarter = null
        startShizukuExternalAfterPermission = false
        serverConnectedListener?.close()
        serverConnectedListener = null
        serverDisconnectedWatcher?.close()
        serverDisconnectedWatcher = null
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
