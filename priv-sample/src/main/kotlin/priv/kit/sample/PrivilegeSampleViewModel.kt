package priv.kit.sample

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import priv.kit.core.Privilege

internal class PrivilegeSampleViewModel : ViewModel() {
    val serverRunning = Privilege.serverState
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Privilege.serverState.value != null)

    val backStack = mutableStateListOf<PrivilegeSampleRootDestination>(
        PrivilegeSampleRootDestination.Home,
    )

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

}
