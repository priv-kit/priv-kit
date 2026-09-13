package priv.kit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import priv.kit.ui.component.PrivilegeTopBar
import priv.kit.ui.resources.*

/**
 * Displays the shared page with an in-memory simulation; no runtime is created.
 * [useLegacyPackaging] selects the simulated manual command's native library packaging format.
 */
@Composable
@Suppress("DEPRECATION")
public fun PrivilegePreviewScaffold(
    modifier: Modifier = Modifier,
    useLegacyPackaging: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val externalLabel = stringResource(Res.string.priv_ui_simulation_external)
    val startingText = mapOf(
        PrivilegeUiRuntimeStartSource.ROOT to stringResource(Res.string.priv_ui_starting_root),
        PrivilegeUiRuntimeStartSource.ADB_WIRELESS to stringResource(Res.string.priv_ui_wireless_adb_starting),
        PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP to stringResource(Res.string.priv_ui_tcp_starting),
        PrivilegeUiRuntimeStartSource.EXTERNAL to stringResource(Res.string.priv_ui_external_starting),
    )
    val pairingText = stringResource(Res.string.priv_ui_pairing_service_found_text)
    val simulation = remember(externalLabel, startingText, pairingText) {
        PrivilegeUiSimulation(scope, externalLabel, startingText, pairingText) {
            clipboard.setText(AnnotatedString(it))
        }
    }
    SideEffect { simulation.setUseLegacyPackaging(useLegacyPackaging) }
    if (simulation.externalAuthorizationRequested) {
        AlertDialog(
            onDismissRequest = simulation::cancelOperation,
            title = { Text(stringResource(Res.string.priv_ui_external_authorize_start)) },
            text = { Text(stringResource(Res.string.priv_ui_simulation_external_confirmation)) },
            confirmButton = {
                TextButton(onClick = simulation::confirmExternalAuthorization) {
                    Text(stringResource(Res.string.priv_ui_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = simulation::cancelOperation) {
                    Text(stringResource(Res.string.priv_ui_pairing_cancel_action))
                }
            },
        )
    }
    PrivilegeScaffoldContent(
        modifier = modifier,
        screenScope = PrivilegeUiScreenScope(
            state = simulation.state,
            actions = simulation.actions,
            interactionEnabled = true,
            showFeedback = { scope.launch { snackbar.showSnackbar(it) } },
        ),
        snackbarHostState = snackbar,
        topBar = { PrivilegeTopBar(onBack = {}, backEnabled = false) },
    )
}
