package priv.kit.sample.debug

import androidx.compose.runtime.Composable

@Composable
internal fun PrivilegeSampleDebugPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDebugDestination,
    selectedStartupTab: PrivilegeStartupTab,
    callbacks: PrivilegeSampleDebugCallbacks,
) {
    when (selectedDestination) {
        PrivilegeSampleDebugDestination.Connection -> ConnectionTestPage(
            state = state,
            selectedDestination = selectedDestination,
            selectedStartupTab = selectedStartupTab,
            notificationPairingRunning = state.notificationPairingRunning,
            callbacks = callbacks,
        )
        PrivilegeSampleDebugDestination.Binder -> BinderTestPage(
            state = state,
            selectedDestination = selectedDestination,
            onDestinationSelected = callbacks.destinationSelected,
            onBackToHome = callbacks.backToHome,
            onGetUserManager = callbacks.binder.getUserManager,
            onGetUsers = callbacks.binder.getUsers,
            onRunImqsNative = callbacks.binder.runImqsNative,
            onStopServer = callbacks.connection.stopServer,
        )
        PrivilegeSampleDebugDestination.UserService -> UserServiceTestPage(
            state = state,
            selectedDestination = selectedDestination,
            onDestinationSelected = callbacks.destinationSelected,
            onBackToHome = callbacks.backToHome,
            onBindDedicatedUserService = callbacks.userService.bindDedicated,
            onCallDedicatedUserService = callbacks.userService.callDedicated,
            onStopDedicatedUserService = callbacks.userService.stopDedicated,
            onBindEmbeddedUserService = callbacks.userService.bindEmbedded,
            onCallEmbeddedUserService = callbacks.userService.callEmbedded,
            onStopEmbeddedUserService = callbacks.userService.stopEmbedded,
            onStopServer = callbacks.connection.stopServer,
        )
    }
}
