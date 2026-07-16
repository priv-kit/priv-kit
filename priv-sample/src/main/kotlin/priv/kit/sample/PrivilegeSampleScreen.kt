package priv.kit.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import priv.kit.sample.ui.PrivilegeUiAuthorizationPage
import priv.kit.sample.ui.rememberPrivilegeSampleUiViewModel

@Composable
internal fun PrivilegeSampleScreen(
    state: PrivilegeSampleScreenState,
    backStack: SnapshotStateList<PrivilegeSampleDestination>,
    selectedStartupTab: PrivilegeStartupTab,
    callbacks: PrivilegeSampleCallbacks,
) {
    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<PrivilegeSampleDestination.Connection> {
                ConnectionTestPage(
                    state = state,
                    selectedDestination = PrivilegeSampleDestination.Connection,
                    selectedStartupTab = selectedStartupTab,
                    notificationPairingRunning = state.notificationPairingRunning,
                    callbacks = callbacks,
                )
            }
            entry<PrivilegeSampleDestination.Binder> {
                BinderTestPage(
                    state = state,
                    selectedDestination = PrivilegeSampleDestination.Binder,
                    onDestinationSelected = callbacks.navigation.destinationSelected,
                    onGetUserManager = callbacks.binder.getUserManager,
                    onGetUsers = callbacks.binder.getUsers,
                    onRunImqsNative = callbacks.binder.runImqsNative,
                    onStopServer = callbacks.connection.stopServer,
                )
            }
            entry<PrivilegeSampleDestination.UserService> {
                UserServiceTestPage(
                    state = state,
                    selectedDestination = PrivilegeSampleDestination.UserService,
                    onDestinationSelected = callbacks.navigation.destinationSelected,
                    onBindDedicatedUserService = callbacks.userService.bindDedicated,
                    onCallDedicatedUserService = callbacks.userService.callDedicated,
                    onStopDedicatedUserService = callbacks.userService.stopDedicated,
                    onBindEmbeddedUserService = callbacks.userService.bindEmbedded,
                    onCallEmbeddedUserService = callbacks.userService.callEmbedded,
                    onStopEmbeddedUserService = callbacks.userService.stopEmbedded,
                    onStopServer = callbacks.connection.stopServer,
                )
            }
            entry<PrivilegeSampleDestination.PrivilegeUi> {
                val viewModel = rememberPrivilegeSampleUiViewModel(callbacks.privilegeUi)
                PrivilegeUiAuthorizationPage(
                    viewModel = viewModel,
                )
            }
        },
    )
}
