package priv.kit.ui.m3

import priv.kit.ui.m3.component.*
import priv.kit.ui.*
import priv.kit.ui.component.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import priv.kit.ui.m3.component.AdbPanel
import priv.kit.ui.m3.component.PermissionRestrictionWarning
import priv.kit.ui.m3.component.AuthorizationModeTabs
import priv.kit.ui.m3.component.AutoRecoveryWarning
import priv.kit.ui.m3.component.ExternalStartPanel
import priv.kit.ui.m3.component.ManualShellPanel
import priv.kit.ui.m3.component.PrivilegeUiSpacing
import priv.kit.ui.m3.component.RootPanel
import priv.kit.ui.m3.component.RestartConfirmationDialog
import priv.kit.ui.m3.component.ServiceStatusPanel
import priv.kit.ui.m3.component.StartupLogPanel

@Composable
internal fun PrivilegeScaffoldContent(
    screenScope: PrivilegeUiScreenScope,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable (SnackbarHostState) -> Unit = { SnackbarHost(it) },
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
) {
    val state = screenScope.state
    val interactionEnabled = screenScope.interactionEnabled
    screenScope.RestartConfirmationDialog()
    with(screenScope) {
        when (state.pairingInteraction) {
            PrivilegeUiPairingInteraction.ENTER_CODE -> WirelessAdbPairingDialog()
            PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION -> WirelessAdbPairingNotificationPermissionWarningDialog()
            null -> Unit
        }
        state.staticTcpSwitchConfirmation?.let { StaticTcpSwitchConfirmationDialog(it) }
        if (state.tcpAuthorizationFailureSerial > m3State.dismissedTcpFailureSerial) TcpAuthorizationFailureDialog()
    }
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = {
            snackbarHost(snackbarHostState)
        },
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PrivilegeUiSpacing.large,
                    top = PrivilegeUiSpacing.medium,
                    end = PrivilegeUiSpacing.large,
                    bottom = PrivilegeUiSpacing.extraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.large),
        ) {
            Column {
                AnimatedVisibility(
                    visible = privilegeUiAutoRecoveryWarningVisible(
                        state = state,
                        interactionEnabled = interactionEnabled,
                    ),
                ) {
                    Column {
                        screenScope.AutoRecoveryWarning()
                        Spacer(Modifier.height(PrivilegeUiSpacing.large))
                    }
                }
                screenScope.ServiceStatusPanel()
                AnimatedVisibility(
                    visible = privilegeUiPermissionRestrictionWarningVisible(
                        runtimeStatus = state.runtimeStatus,
                        restrictionStatus = state.permissionRestrictionStatus,
                    ),
                    enter = expandVertically(expandFrom = Alignment.Top),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    Column {
                        Spacer(Modifier.height(PrivilegeUiSpacing.large))
                        screenScope.PermissionRestrictionWarning()
                    }
                }
            }
            screenScope.AuthorizationModeTabs()
            screenScope.AuthorizationModePanel()
            if (state.startupLogLines.isNotEmpty()) {
                screenScope.StartupLogPanel()
            }
        }
    }
}

@ExperimentalPrivilegeUiApi
public class PrivilegeUiScreenScope(
    public val state: PrivilegeUiScreenState,
    public val actions: PrivilegeUiActions,
    public val m3State: PrivilegeM3State,
    public val m3Actions: PrivilegeM3Actions,
    public val interactionEnabled: Boolean,
    public val showFeedback: (String) -> Unit,
    public val onViewPermissionSolutions: (() -> Unit)? = null,
    public val startupTutorialUrl: String? = null,
    public val onViewStartupTutorial: ((PrivilegeUiTutorialTopic) -> Unit)? = null,
)

@Composable
private fun PrivilegeUiScreenScope.AuthorizationModePanel() {
    val mode = m3State.selectedStartupMode.takeIf { it in state.startupModes }
        ?: state.startupModes.first()
    when (mode) {
        PrivilegeUiStartupMode.ROOT -> RootPanel()
        PrivilegeUiStartupMode.MANUAL_SHELL -> ManualShellPanel()
        PrivilegeUiStartupMode.ADB -> AdbPanel()
        PrivilegeUiStartupMode.EXTERNAL -> ExternalStartPanel()
    }
}
