package priv.kit.sample.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import priv.kit.sample.R

@Composable
internal fun ConnectionTestPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDebugDestination,
    selectedStartupTab: PrivilegeStartupTab,
    notificationPairingRunning: Boolean,
    callbacks: PrivilegeSampleDebugCallbacks,
) {
    SamplePageScaffold(
        title = stringResource(R.string.sample_test_authorization),
        selectedDestination = selectedDestination,
        busy = state.busy,
        onDestinationSelected = callbacks.destinationSelected,
        onBackToHome = callbacks.backToHome,
        actions = {
            SampleTopBarAction(
                label = stringResource(R.string.sample_open_privilege_ui),
                enabled = !state.busy,
                onClick = callbacks.openPrivilegeUi,
            )
        },
    ) {
        StatusPanel(state, callbacks.connection.stopServer)
        StartupTabs(
            selectedStartupTab = selectedStartupTab,
            busy = state.busy,
            onStartupTabSelected = callbacks.startupTabSelected,
        )
        when (selectedStartupTab) {
            PrivilegeStartupTab.Root -> RootPage(state, callbacks.connection.startRootRuntime)
            PrivilegeStartupTab.Manual -> ManualPage(state, callbacks.connection.copyManualCommand)
            PrivilegeStartupTab.Shizuku -> ShizukuPage(
                state = state,
                onStartShizukuExternal = callbacks.connection.startShizukuExternal,
            )
            PrivilegeStartupTab.WirelessAdb -> WirelessAdbPage(
                state = state,
                notificationPairingRunning = notificationPairingRunning,
                onAdbDeviceNameChanged = callbacks.connection.adbDeviceNameChanged,
                onRefreshAdbFingerprint = callbacks.connection.refreshAdbFingerprint,
                onCheckAdbPairing = callbacks.connection.checkAdbPairing,
                onPairingCodeChanged = callbacks.connection.pairingCodeChanged,
                onCopyLog = callbacks.log.copy,
                onPairWirelessAdb = callbacks.connection.pairWirelessAdb,
                onStartNotificationPairing = callbacks.connection.startNotificationPairing,
                onStopNotificationPairing = callbacks.connection.stopNotificationPairing,
                onStartWirelessAdb = callbacks.connection.startWirelessAdb,
            )
            PrivilegeStartupTab.Tcp -> TcpPage(
                state = state,
                onTcpPortChanged = callbacks.connection.tcpPortChanged,
                onSwitchToTcp = callbacks.connection.switchToTcp,
                onRestartTcp = callbacks.connection.restartTcp,
                onStopTcp = callbacks.connection.stopTcp,
            )
            PrivilegeStartupTab.Log -> SessionPage(state, callbacks.log.clear, callbacks.log.copy)
        }
    }
}

@Composable
private fun StartupTabs(
    selectedStartupTab: PrivilegeStartupTab,
    busy: Boolean,
    onStartupTabSelected: (PrivilegeStartupTab) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrivilegeStartupTab.entries.chunked(3).forEach { rowTabs ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTabs.forEach { tab ->
                    val selected = selectedStartupTab == tab
                    StartupTabButton(
                        label = stringResource(tab.titleRes),
                        selected = selected,
                        enabled = !busy || selected,
                        modifier = Modifier.weight(1f),
                    ) {
                        onStartupTabSelected(tab)
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupTabButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        selected -> colors.primary
        enabled -> colors.surfaceContainerHigh
        else -> colors.onSurface.copy(alpha = 0.12f)
    }
    val foreground = when {
        selected -> colors.onPrimary
        enabled -> colors.onSurfaceVariant
        else -> colors.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = modifier
            .height(42.dp)
            .widthIn(min = 88.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = foreground,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun RootPage(
    state: PrivilegeSampleScreenState,
    onStartRootRuntime: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SampleAction(
            label = if (state.busy && state.status == PrivilegeSampleStatus.STARTING) {
                stringResource(R.string.sample_starting_root_runtime)
            } else {
                stringResource(R.string.sample_start_root_runtime)
            },
            enabled = !state.busy,
            tone = SampleActionTone.Primary,
            modifier = Modifier,
            onClick = onStartRootRuntime,
        )
    }
}

@Composable
private fun ManualPage(
    state: PrivilegeSampleScreenState,
    onCopyManualCommand: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.manualShellCommandLine != null) {
            val commandLine = state.manualShellCommandLine
            CommandBlock(commandLine = commandLine, onCopy = onCopyManualCommand)
        } else {
            SampleAction(
                label = stringResource(R.string.sample_manual_shell_command_unavailable),
                enabled = false,
                tone = SampleActionTone.Neutral,
                modifier = Modifier,
                onClick = {},
            )
        }
    }
}

@Composable
private fun ShizukuPage(
    state: PrivilegeSampleScreenState,
    onStartShizukuExternal: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerLow)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RuntimeInfoRow(label = stringResource(R.string.sample_ready), value = if (state.shizukuReady) stringResource(R.string.sample_yes) else stringResource(R.string.sample_no))
            RuntimeInfoRow(label = stringResource(R.string.sample_permission), value = if (state.shizukuPermissionGranted) stringResource(R.string.sample_granted) else "-")
            RuntimeInfoRow(label = "uid", value = state.shizukuUid?.toString() ?: "-")
            RuntimeInfoRow(label = stringResource(R.string.sample_version), value = state.shizukuVersion?.toString() ?: "-")
            SelectionContainer {
                BasicText(
                    text = state.shizukuMessage,
                    style = TextStyle(
                        color = colors.onSurfaceVariant,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                )
            }
        }
        SampleAction(
            label = state.shizukuExternalActionLabel(),
            enabled = !state.busy,
            tone = SampleActionTone.Primary,
            modifier = Modifier,
            onClick = onStartShizukuExternal,
        )
        if (state.shizukuLastException.isNotBlank()) {
            DiagnosticBlock(state.shizukuLastException)
        }
    }
}

@Composable
private fun PrivilegeSampleScreenState.shizukuExternalActionLabel(): String =
    if (shizukuReady && shizukuPermissionGranted) {
        stringResource(R.string.sample_start_with_shizuku)
    } else {
        stringResource(R.string.sample_authorize_and_start_shizuku)
    }

@Composable
private fun PairingStatusPanel(
    status: PrivilegeAdbPairingStatus,
    message: String,
    fingerprint: String?,
    fingerprintLoading: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val background = when (status) {
        PrivilegeAdbPairingStatus.PAIRED -> colors.tertiaryContainer
        PrivilegeAdbPairingStatus.FAILED -> colors.errorContainer
        PrivilegeAdbPairingStatus.CHECKING,
        PrivilegeAdbPairingStatus.SEARCHING,
        PrivilegeAdbPairingStatus.PAIRING,
        PrivilegeAdbPairingStatus.FOUND,
        -> colors.primaryContainer
        PrivilegeAdbPairingStatus.NOT_PAIRED -> colors.surfaceContainerHigh
    }
    val foreground = when (status) {
        PrivilegeAdbPairingStatus.PAIRED -> colors.onTertiaryContainer
        PrivilegeAdbPairingStatus.FAILED -> colors.onErrorContainer
        PrivilegeAdbPairingStatus.CHECKING,
        PrivilegeAdbPairingStatus.SEARCHING,
        PrivilegeAdbPairingStatus.PAIRING,
        PrivilegeAdbPairingStatus.FOUND,
        -> colors.onPrimaryContainer
        PrivilegeAdbPairingStatus.NOT_PAIRED -> colors.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(foreground),
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(
                text = stringResource(R.string.sample_pairing_status, stringResource(status.labelRes)),
                style = TextStyle(
                    color = foreground,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        BasicText(
            text = message,
            style = TextStyle(
                color = foreground.copy(alpha = 0.82f),
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        )
        BasicText(
            text = stringResource(R.string.sample_fingerprint, when {
                fingerprintLoading -> stringResource(R.string.sample_loading)
                fingerprint != null -> fingerprint
                else -> stringResource(R.string.sample_not_loaded)
            }),
            style = TextStyle(
                color = foreground.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
        )
    }
}

@Composable
private fun WirelessAdbPage(
    state: PrivilegeSampleScreenState,
    notificationPairingRunning: Boolean,
    onAdbDeviceNameChanged: (String) -> Unit,
    onRefreshAdbFingerprint: () -> Unit,
    onCheckAdbPairing: () -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onCopyLog: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onStartNotificationPairing: () -> Unit,
    onStopNotificationPairing: () -> Unit,
    onStartWirelessAdb: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PairingStatusPanel(
            status = state.pairingStatus,
            message = state.pairingMessage,
            fingerprint = state.adbKeyFingerprint,
            fingerprintLoading = state.adbKeyFingerprintLoading,
        )
        SampleField(
            label = stringResource(R.string.sample_adb_device_name_blank_app_name),
            value = state.adbDeviceNameText,
            onValueChange = onAdbDeviceNameChanged,
            keyboardOptions = KeyboardOptions.Default,
        )
        RuntimeInfoRow(label = stringResource(R.string.sample_adb_name), value = state.adbDeviceName)
        RuntimeInfoRow(label = stringResource(R.string.sample_key_source), value = stringResource(R.string.sample_persisted_adb_key))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = stringResource(R.string.sample_refresh_identity),
                enabled = !state.busy && !state.adbKeyFingerprintLoading,
                tone = SampleActionTone.Tonal,
                modifier = Modifier.weight(1f),
                onClick = onRefreshAdbFingerprint,
            )
            SampleAction(
                label = stringResource(R.string.sample_check_pairing),
                enabled = !state.busy && !state.adbKeyFingerprintLoading,
                tone = SampleActionTone.Secondary,
                modifier = Modifier.weight(1f),
                onClick = onCheckAdbPairing,
            )
        }
        SampleAction(
            label = stringResource(R.string.sample_copy_wireless_log),
            enabled = true,
            tone = SampleActionTone.Neutral,
            modifier = Modifier,
            onClick = onCopyLog,
        )
        SampleField(
            label = stringResource(R.string.sample_pairing_code),
            value = state.pairingCode,
            onValueChange = onPairingCodeChanged,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        RuntimeInfoRow(label = stringResource(R.string.sample_connect_port), value = state.connectPortText.ifBlank { stringResource(R.string.sample_auto) })
        SampleAction(
            label = stringResource(R.string.sample_pair_by_code),
            enabled = !state.busy,
            tone = SampleActionTone.Primary,
            modifier = Modifier,
            onClick = onPairWirelessAdb,
        )
        SampleAction(
            label = if (notificationPairingRunning) {
                stringResource(R.string.sample_stop_notification_pairing)
            } else {
                stringResource(R.string.sample_pair_via_notification)
            },
            enabled = !state.busy || notificationPairingRunning,
            tone = if (notificationPairingRunning) {
                SampleActionTone.Destructive
            } else {
                SampleActionTone.Primary
            },
            modifier = Modifier,
            onClick = if (notificationPairingRunning) onStopNotificationPairing else onStartNotificationPairing,
        )
        SampleAction(
            label = stringResource(R.string.sample_start_wireless_adb),
            enabled = !state.busy,
            tone = SampleActionTone.Primary,
            modifier = Modifier,
            onClick = onStartWirelessAdb,
        )
    }
}

@Composable
private fun TcpPage(
    state: PrivilegeSampleScreenState,
    onTcpPortChanged: (String) -> Unit,
    onSwitchToTcp: () -> Unit,
    onRestartTcp: () -> Unit,
    onStopTcp: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RuntimeInfoRow(label = stringResource(R.string.sample_last_connect_port), value = state.connectPortText.ifBlank { stringResource(R.string.sample_auto) })
        SampleField(
            label = stringResource(R.string.sample_tcp_port),
            value = state.tcpPortText,
            onValueChange = onTcpPortChanged,
            keyboardOptions = KeyboardOptions.Default,
        )
        SampleAction(
            label = stringResource(R.string.sample_switch_to_tcp_mode),
            enabled = !state.busy,
            tone = SampleActionTone.Secondary,
            modifier = Modifier,
            onClick = onSwitchToTcp,
        )
        SampleAction(
            label = stringResource(R.string.sample_restart_from_tcp_port),
            enabled = !state.busy,
            tone = SampleActionTone.Primary,
            modifier = Modifier,
            onClick = onRestartTcp,
        )
        SampleAction(
            label = stringResource(R.string.sample_stop_tcp_mode),
            enabled = !state.busy,
            tone = SampleActionTone.Neutral,
            modifier = Modifier,
            onClick = onStopTcp,
        )
    }
}

@Composable
private fun SessionPage(
    state: PrivilegeSampleScreenState,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = stringResource(R.string.sample_copy_log),
                enabled = state.logText.isNotBlank(),
                tone = SampleActionTone.Primary,
                modifier = Modifier.weight(1f),
                onClick = onCopyLog,
            )
            SampleAction(
                label = stringResource(R.string.sample_clear_log),
                enabled = !state.busy,
                tone = SampleActionTone.Neutral,
                modifier = Modifier.weight(1f),
                onClick = onClearLog,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerHighest)
                .padding(16.dp),
        ) {
            SelectionContainer {
                BasicText(
                    text = state.logText.ifBlank { "-" },
                    style = TextStyle(
                        color = colors.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CommandBlock(
    commandLine: String,
    onCopy: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerHighest)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = stringResource(R.string.sample_run_starter_inside_adb_shell),
            style = TextStyle(
                color = colors.onSurfaceVariant,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        BasicText(
            text = commandLine,
            style = TextStyle(
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
        )
        SampleAction(
            label = stringResource(R.string.sample_copy_command),
            enabled = true,
            tone = SampleActionTone.Primary,
            modifier = Modifier,
            onClick = onCopy,
        )
    }
}
