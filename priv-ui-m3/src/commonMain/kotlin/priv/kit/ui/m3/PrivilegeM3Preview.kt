package priv.kit.ui.m3

import priv.kit.ui.m3.resources.*
import priv.kit.ui.resources.Res as UiRes
import priv.kit.ui.resources.priv_ui_external_starting
import priv.kit.ui.resources.priv_ui_pairing_service_found_text
import priv.kit.ui.resources.priv_ui_starting_root
import priv.kit.ui.resources.priv_ui_tcp_starting
import priv.kit.ui.resources.priv_ui_wireless_adb_starting
import priv.kit.ui.*
import priv.kit.ui.component.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import priv.kit.ui.m3.component.PrivilegeTopBar

/**
 * Displays the shared page with an in-memory simulation; no runtime is created.
 * [useLegacyPackaging] selects the simulated manual command's native library packaging format.
 * [adbRestricted] controls permission restrictions for simulated Shell connections.
 * [batteryOptimizationExempt] and [localNetworkPermissionGranted] control simulated permission cards.
 * [onPermissionsChanged] reports successful simulated requests to the host controls.
 * [onViewPermissionSolutions] overrides opening the localized permission troubleshooting page.
 * [startupTutorialUrl] replaces the localized guide URL for every help topic.
 * [onViewStartupTutorial] overrides guide navigation; [onViewPermissionSolutions] takes
 * precedence for permission help. Topic anchors are stable across guide languages.
 */
@Composable
@Suppress("DEPRECATION")
public fun PrivilegeM3Preview(
    modifier: Modifier = Modifier,
    useLegacyPackaging: Boolean = true,
    adbRestricted: Boolean = true,
    batteryOptimizationExempt: Boolean = true,
    localNetworkPermissionGranted: Boolean = true,
    onPermissionsChanged: ((batteryOptimizationExempt: Boolean, localNetworkPermissionGranted: Boolean) -> Unit)? = null,
    onViewPermissionSolutions: (() -> Unit)? = null,
    startupTutorialUrl: String? = null,
    onViewStartupTutorial: ((PrivilegeUiTutorialTopic) -> Unit)? = null,
) {
    val permissionsChanged by rememberUpdatedState(onPermissionsChanged)
    val scope = rememberCoroutineScope()
    val m3 = remember(scope) { PrivilegeM3Controller(scope) }
    androidx.compose.runtime.DisposableEffect(m3) { onDispose { m3.close() } }
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val externalLabel = stringResource(Res.string.priv_ui_simulation_external)
    val startingText = mapOf(
        PrivilegeUiRuntimeStartSource.ROOT to stringResource(UiRes.string.priv_ui_starting_root),
        PrivilegeUiRuntimeStartSource.ADB_WIRELESS to stringResource(UiRes.string.priv_ui_wireless_adb_starting),
        PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP to stringResource(UiRes.string.priv_ui_tcp_starting),
        PrivilegeUiRuntimeStartSource.EXTERNAL to stringResource(UiRes.string.priv_ui_external_starting),
    )
    val pairingText = stringResource(UiRes.string.priv_ui_pairing_service_found_text)
    val simulation = remember(externalLabel, startingText, pairingText) {
        PrivilegeUiSimulation(scope, externalLabel, startingText, pairingText,
            onPermissionsChanged = { battery, network -> permissionsChanged?.invoke(battery, network) },
        ) {
            clipboard.setText(AnnotatedString(it))
        }
    }
    SideEffect {
        simulation.setUseLegacyPackaging(useLegacyPackaging)
        simulation.setAdbRestricted(adbRestricted)
    }
    androidx.compose.runtime.LaunchedEffect(simulation, batteryOptimizationExempt, localNetworkPermissionGranted) {
        simulation.setPermissions(batteryOptimizationExempt, localNetworkPermissionGranted)
    }
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
    PrivilegeM3Screen(
        modifier = modifier,
        state = simulation.state,
        actions = simulation.actions,
        m3State = m3.state,
        m3Actions = m3.actions,
        interactionEnabled = true,
        showFeedback = { scope.launch { snackbar.showSnackbar(it) } },
        onViewPermissionSolutions = onViewPermissionSolutions,
        startupTutorialUrl = startupTutorialUrl,
        onViewStartupTutorial = onViewStartupTutorial,
        snackbarHostState = snackbar,
        topBar = { PrivilegeTopBar(onBack = {}, backEnabled = false) },
    )
}
