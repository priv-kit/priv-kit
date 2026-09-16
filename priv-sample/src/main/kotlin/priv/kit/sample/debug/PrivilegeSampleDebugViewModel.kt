package priv.kit.sample.debug

import android.annotation.SuppressLint
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var deniedPermissionsJob: Job? = null
    private var deniedPermissionsServer: PrivilegeServerInfo? = null

    fun updateDeniedPermissionsServer(serverInfo: PrivilegeServerInfo?) {
        if (deniedPermissionsServer == serverInfo) return
        deniedPermissionsServer = serverInfo
        deniedPermissionsJob?.cancel()
        deniedPermissionsJob = null
        screenState = screenState.copy(
            deniedPermissionsLoading = false,
            deniedPermissions = null,
            deniedPermissionsError = null,
        )
        if (selectedDebugDestination == PrivilegeSampleDebugDestination.Permissions) refreshDeniedPermissions()
    }

    fun refreshDeniedPermissions() {
        val server = deniedPermissionsServer ?: return
        if (screenState.deniedPermissionsLoading || Privilege.serverState.value != server) return
        screenState = screenState.copy(
            deniedPermissionsLoading = true,
            deniedPermissions = null,
            deniedPermissionsError = null,
        )
        deniedPermissionsJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    check(Privilege.serverState.value == server) { "Server connection changed" }
                    Result.success(Privilege.getDeniedServerPermissions())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Result.failure(exception)
                }
            }
            if (deniedPermissionsServer != server || Privilege.serverState.value != server) return@launch
            screenState = screenState.copy(
                deniedPermissionsLoading = false,
                deniedPermissions = result.getOrNull(),
                deniedPermissionsError = result.exceptionOrNull()?.let { it.message ?: it.javaClass.name },
            )
        }
    }
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

    fun selectDebugDestination(destination: PrivilegeSampleDebugDestination) {
        selectedDebugDestination = destination
        if (destination == PrivilegeSampleDebugDestination.Permissions && screenState.deniedPermissions == null &&
            screenState.deniedPermissionsError == null
        ) {
            refreshDeniedPermissions()
        }
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
        val embeddedConnection = embeddedUserServiceConnection
        embeddedUserServiceConnection = null
        dedicatedUserService = null
        val dedicatedConnection = dedicatedUserServiceConnection
        dedicatedUserServiceConnection = null
        userServiceCleanupScope.launch {
            runCatching { embeddedConnection?.unbind() }
            runCatching { dedicatedConnection?.unbind() }
        }
    }

    private companion object {
        val userServiceCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
