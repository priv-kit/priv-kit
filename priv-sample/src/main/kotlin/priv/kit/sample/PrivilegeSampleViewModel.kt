package priv.kit.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

internal class PrivilegeSampleViewModel : ViewModel() {
    var screenState by mutableStateOf(PrivilegeSampleScreenState())

    val backStack = mutableStateListOf<PrivilegeSampleDestination>(
        PrivilegeSampleDestination.Connection,
    )

    fun selectDestination(destination: PrivilegeSampleDestination) {
        if (backStack.lastOrNull() == destination) return
        backStack.clear()
        backStack += destination
    }
}
