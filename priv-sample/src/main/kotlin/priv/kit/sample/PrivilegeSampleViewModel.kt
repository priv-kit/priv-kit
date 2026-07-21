package priv.kit.sample

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import priv.kit.core.Privilege

internal class PrivilegeSampleViewModel : ViewModel() {
    private val mutableServerRunning = MutableStateFlow(false)
    val serverRunning = mutableServerRunning.asStateFlow()
    private val serverConnectedListener = Privilege.addServerConnectedListener {
        mutableServerRunning.value = true
    }
    private val serverDisconnectedListener = Privilege.addServerDisconnectedListener {
        mutableServerRunning.value = false
    }

    val backStack = mutableStateListOf<PrivilegeSampleRootDestination>(
        PrivilegeSampleRootDestination.Home,
    )

    init {
        mutableServerRunning.value = Privilege.pingServer()
    }

    fun openDebug() {
        openRootDestination(PrivilegeSampleRootDestination.Debug)
    }

    fun openPrivilegeUi() {
        openRootDestination(PrivilegeSampleRootDestination.PrivilegeUi)
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    private fun openRootDestination(destination: PrivilegeSampleRootDestination) {
        if (backStack.lastOrNull() == destination) return
        backStack += destination
    }

    override fun onCleared() {
        serverConnectedListener.close()
        serverDisconnectedListener.close()
        super.onCleared()
    }
}
