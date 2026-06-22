package priv.kit.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

internal class PrivilegeSampleViewModel : ViewModel() {
    var screenState by mutableStateOf(PrivilegeSampleScreenState())
    var selectedStartupTab by mutableStateOf<PrivilegeStartupTab>(PrivilegeStartupTab.Root)

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
}
