package priv.kit.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import priv.kit.PrivilegeServerInfo
import priv.kit.ui.PrivilegeUiViewModel

@Composable
internal fun PrivilegeSampleScreen(
    state: PrivilegeSampleScreenState,
    backStack: SnapshotStateList<PrivilegeSampleDestination>,
    selectedStartupTab: PrivilegeStartupTab,
    privilegeUiViewModel: PrivilegeUiViewModel,
    notificationPairingRunning: Boolean,
    onDestinationSelected: (PrivilegeSampleDestination) -> Unit,
    onStartupTabSelected: (PrivilegeStartupTab) -> Unit,
    onOpenPrivilegeUi: () -> Unit,
    onPrivilegeUiBack: () -> Unit,
    onPrivilegeUiHelp: () -> Unit,
    onPrivilegeUiConnected: (PrivilegeServerInfo) -> Unit,
    onPrivilegeUiNotificationPermissionRequired: () -> Unit,
    onAdbDeviceNameChanged: (String) -> Unit,
    onRefreshAdbFingerprint: () -> Unit,
    onCheckAdbPairing: () -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onTcpPortChanged: (String) -> Unit,
    onStartRootRuntime: () -> Unit,
    onCopyManualCommand: () -> Unit,
    onStartShizukuExternal: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onStartNotificationPairing: () -> Unit,
    onStopNotificationPairing: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onSwitchToTcp: () -> Unit,
    onRestartTcp: () -> Unit,
    onStopTcp: () -> Unit,
    onStopServer: () -> Unit,
    onGetUserManager: () -> Unit,
    onGetUsers: () -> Unit,
    onRunImqsNative: () -> Unit,
    onBindDedicatedUserService: () -> Unit,
    onCallDedicatedUserService: () -> Unit,
    onStopDedicatedUserService: () -> Unit,
    onBindEmbeddedUserService: () -> Unit,
    onCallEmbeddedUserService: () -> Unit,
    onStopEmbeddedUserService: () -> Unit,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
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
                    notificationPairingRunning = notificationPairingRunning,
                    onDestinationSelected = onDestinationSelected,
                    onStartupTabSelected = onStartupTabSelected,
                    onOpenPrivilegeUi = onOpenPrivilegeUi,
                    onAdbDeviceNameChanged = onAdbDeviceNameChanged,
                    onRefreshAdbFingerprint = onRefreshAdbFingerprint,
                    onCheckAdbPairing = onCheckAdbPairing,
                    onPairingCodeChanged = onPairingCodeChanged,
                    onTcpPortChanged = onTcpPortChanged,
                    onStartRootRuntime = onStartRootRuntime,
                    onCopyManualCommand = onCopyManualCommand,
                    onStartShizukuExternal = onStartShizukuExternal,
                    onPairWirelessAdb = onPairWirelessAdb,
                    onStartNotificationPairing = onStartNotificationPairing,
                    onStopNotificationPairing = onStopNotificationPairing,
                    onStartWirelessAdb = onStartWirelessAdb,
                    onSwitchToTcp = onSwitchToTcp,
                    onRestartTcp = onRestartTcp,
                    onStopTcp = onStopTcp,
                    onStopServer = onStopServer,
                    onClearLog = onClearLog,
                    onCopyLog = onCopyLog,
                )
            }
            entry<PrivilegeSampleDestination.Binder> {
                BinderTestPage(
                    state = state,
                    selectedDestination = PrivilegeSampleDestination.Binder,
                    onDestinationSelected = onDestinationSelected,
                    onGetUserManager = onGetUserManager,
                    onGetUsers = onGetUsers,
                    onRunImqsNative = onRunImqsNative,
                    onStopServer = onStopServer,
                )
            }
            entry<PrivilegeSampleDestination.UserService> {
                UserServiceTestPage(
                    state = state,
                    selectedDestination = PrivilegeSampleDestination.UserService,
                    onDestinationSelected = onDestinationSelected,
                    onBindDedicatedUserService = onBindDedicatedUserService,
                    onCallDedicatedUserService = onCallDedicatedUserService,
                    onStopDedicatedUserService = onStopDedicatedUserService,
                    onBindEmbeddedUserService = onBindEmbeddedUserService,
                    onCallEmbeddedUserService = onCallEmbeddedUserService,
                    onStopEmbeddedUserService = onStopEmbeddedUserService,
                    onStopServer = onStopServer,
                )
            }
            entry<PrivilegeSampleDestination.PrivilegeUi> {
                PrivilegeUiAuthorizationPage(
                    viewModel = privilegeUiViewModel,
                    onBackClick = onPrivilegeUiBack,
                    onHelpClick = onPrivilegeUiHelp,
                    onConnected = onPrivilegeUiConnected,
                    onNotificationPermissionRequired = onPrivilegeUiNotificationPermissionRequired,
                )
            }
        },
    )
}
