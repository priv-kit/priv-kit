package priv.kit.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import priv.kit.ui.component.AdbPanel
import priv.kit.ui.component.PermissionRestrictionWarning
import priv.kit.ui.component.AuthorizationModeTabs
import priv.kit.ui.component.AutoRecoveryWarning
import priv.kit.ui.component.ExternalStartPanel
import priv.kit.ui.component.ManualShellPanel
import priv.kit.ui.component.PrivilegeUiSpacing
import priv.kit.ui.component.RootPanel
import priv.kit.ui.component.RestartConfirmationDialog
import priv.kit.ui.component.ServiceStatusPanel
import priv.kit.ui.component.StartupLogPanel
import priv.kit.ui.component.privilegeUiAutoRecoveryWarningVisible

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
            }
            screenScope.PermissionRestrictionWarning()
            screenScope.AuthorizationModeTabs()
            screenScope.AuthorizationModePanel()
            if (state.startupLogLines.isNotEmpty()) {
                screenScope.StartupLogPanel()
            }
        }
    }
}

internal fun privilegeUiAutoRecoveryWarningVisible(
    state: PrivilegeUiScreenState,
    interactionEnabled: Boolean,
): Boolean = interactionEnabled &&
    privilegeUiAutoRecoveryWarningVisible(
        desiredEnabled = state.desiredEnabled,
        runtimeStatus = state.runtimeStatus,
        runtimeStartPhase = state.runtimeStartPhase,
    )

internal class PrivilegeUiScreenScope(
    val state: PrivilegeUiScreenState,
    val actions: PrivilegeUiActions,
    val interactionEnabled: Boolean,
    val showFeedback: (String) -> Unit,
)

@Composable
private fun PrivilegeUiScreenScope.AuthorizationModePanel() {
    val mode = state.selectedStartupMode.takeIf { it in state.startupModes }
        ?: state.startupModes.first()
    when (mode) {
        PrivilegeUiStartupMode.ROOT -> RootPanel()
        PrivilegeUiStartupMode.MANUAL_SHELL -> ManualShellPanel()
        PrivilegeUiStartupMode.ADB -> AdbPanel()
        PrivilegeUiStartupMode.EXTERNAL -> ExternalStartPanel()
    }
}
