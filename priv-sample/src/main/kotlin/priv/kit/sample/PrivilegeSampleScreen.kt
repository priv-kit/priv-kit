package priv.kit.sample

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import priv.kit.sample.debug.PrivilegeSampleDebugCallbacks
import priv.kit.sample.debug.PrivilegeSampleDebugDestination
import priv.kit.sample.debug.PrivilegeSampleDebugPage
import priv.kit.sample.debug.PrivilegeSampleScreenState
import priv.kit.sample.debug.PrivilegeStartupTab
import priv.kit.sample.home.PrivilegeSampleHomePage
import priv.kit.sample.startup.PrivilegeSamplePrivilegeUiCallbacks
import priv.kit.sample.startup.PrivilegeUiAuthorizationPage
import priv.kit.sample.startup.rememberPrivilegeSampleUiViewModel

@Composable
internal fun PrivilegeSampleScreen(
    serverRunning: Boolean,
    state: PrivilegeSampleScreenState,
    backStack: SnapshotStateList<PrivilegeSampleRootDestination>,
    selectedDebugDestination: PrivilegeSampleDebugDestination,
    selectedStartupTab: PrivilegeStartupTab,
    onOpenDebug: () -> Unit,
    onOpenPrivilegeUi: () -> Unit,
    onDebugStarted: () -> Unit,
    onDebugStopped: () -> Unit,
    debugCallbacks: PrivilegeSampleDebugCallbacks,
    privilegeUiCallbacks: PrivilegeSamplePrivilegeUiCallbacks,
) {
    val debugInBackStack = PrivilegeSampleRootDestination.Debug in backStack
    DisposableEffect(debugInBackStack) {
        if (debugInBackStack) {
            onDebugStarted()
        }
        onDispose {
            if (debugInBackStack) {
                onDebugStopped()
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = {
            slideInHorizontally(initialOffsetX = { it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { -it })
        },
        popTransitionSpec = {
            slideInHorizontally(initialOffsetX = { -it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { it })
        },
        predictivePopTransitionSpec = {
            slideInHorizontally(initialOffsetX = { -it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { it })
        },
        entryProvider = entryProvider {
            entry<PrivilegeSampleRootDestination.Home> {
                PrivilegeSampleHomePage(
                    serverRunning = serverRunning,
                    onOpenPrivilegeUi = onOpenPrivilegeUi,
                    onOpenDebug = onOpenDebug,
                )
            }
            entry<PrivilegeSampleRootDestination.Debug> {
                PrivilegeSampleDebugPage(
                    state = state,
                    selectedDestination = selectedDebugDestination,
                    selectedStartupTab = selectedStartupTab,
                    callbacks = debugCallbacks,
                )
            }
            entry<PrivilegeSampleRootDestination.PrivilegeUi> {
                val viewModel = rememberPrivilegeSampleUiViewModel(privilegeUiCallbacks)
                PrivilegeUiAuthorizationPage(
                    viewModel = viewModel,
                )
            }
        },
    )
}
