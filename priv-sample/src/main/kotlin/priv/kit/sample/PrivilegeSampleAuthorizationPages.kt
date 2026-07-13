package priv.kit.sample

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ConnectionTestPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDestination,
    selectedStartupTab: PrivilegeStartupTab,
    notificationPairingRunning: Boolean,
    callbacks: PrivilegeSampleCallbacks,
) {
    SamplePageScaffold(
        title = "Test Authorization",
        selectedDestination = selectedDestination,
        busy = state.busy,
        onDestinationSelected = callbacks.navigation.destinationSelected,
        actions = {
            SampleTopBarAction(
                label = stringResource(R.string.sample_open_privilege_ui),
                enabled = !state.busy,
                onClick = callbacks.privilegeUi.open,
            )
        },
    ) {
        StatusPanel(state, callbacks.connection.stopServer)
        StartupTabs(
            selectedStartupTab = selectedStartupTab,
            busy = state.busy,
            onStartupTabSelected = callbacks.navigation.startupTabSelected,
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
                        label = tab.title,
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = sampleColors
    val background = when {
        selected -> colors.tabSelectedBackground
        enabled -> colors.tabBackground
        else -> colors.tabDisabledBackground
    }
    val foreground = if (selected) colors.actionForeground else colors.textSecondary
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
    val colors = sampleColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SampleAction(
            label = if (state.busy && state.status == PrivilegeSampleStatus.STARTING) {
                "Starting Root Runtime..."
            } else {
                "Start Root Runtime"
            },
            enabled = !state.busy,
            background = colors.actionPrimary,
            onClick = onStartRootRuntime,
        )
    }
}

@Composable
private fun ManualPage(
    state: PrivilegeSampleScreenState,
    onCopyManualCommand: () -> Unit,
) {
    val colors = sampleColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.manualShellCommandLine != null) {
            val commandLine = state.manualShellCommandLine
            CommandBlock(commandLine = commandLine, onCopy = onCopyManualCommand)
        } else {
            SampleAction(
                label = "Manual Shell Command Unavailable",
                enabled = false,
                background = colors.actionMuted,
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
    val colors = sampleColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.panelBackground)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RuntimeInfoRow(label = "ready", value = if (state.shizukuReady) "yes" else "no")
            RuntimeInfoRow(label = "permission", value = if (state.shizukuPermissionGranted) "granted" else "-")
            RuntimeInfoRow(label = "uid", value = state.shizukuUid?.toString() ?: "-")
            RuntimeInfoRow(label = "version", value = state.shizukuVersion?.toString() ?: "-")
            SelectionContainer {
                BasicText(
                    text = state.shizukuMessage,
                    style = TextStyle(
                        color = colors.textMuted,
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
            background = colors.actionPrimary,
            onClick = onStartShizukuExternal,
        )
        if (state.shizukuLastException.isNotBlank()) {
            DiagnosticBlock(state.shizukuLastException)
        }
    }
}

private fun PrivilegeSampleScreenState.shizukuExternalActionLabel(): String =
    if (shizukuReady && shizukuPermissionGranted) {
        "Start with Shizuku"
    } else {
        "Authorize and Start Shizuku"
    }

@Composable
private fun PairingStatusPanel(
    status: PrivilegeAdbPairingStatus,
    message: String,
    fingerprint: String?,
    fingerprintLoading: Boolean,
) {
    val colors = sampleColors
    val background = when (status) {
        PrivilegeAdbPairingStatus.PAIRED -> colors.statusSuccessBackground
        PrivilegeAdbPairingStatus.FAILED -> colors.statusDangerBackground
        PrivilegeAdbPairingStatus.CHECKING,
        PrivilegeAdbPairingStatus.SEARCHING,
        PrivilegeAdbPairingStatus.PAIRING,
        PrivilegeAdbPairingStatus.FOUND,
        -> colors.statusInfoBackground
        PrivilegeAdbPairingStatus.NOT_PAIRED -> colors.statusNeutralBackground
    }
    val foreground = when (status) {
        PrivilegeAdbPairingStatus.PAIRED -> colors.statusSuccessForeground
        PrivilegeAdbPairingStatus.FAILED -> colors.statusDangerForeground
        PrivilegeAdbPairingStatus.CHECKING,
        PrivilegeAdbPairingStatus.SEARCHING,
        PrivilegeAdbPairingStatus.PAIRING,
        PrivilegeAdbPairingStatus.FOUND,
        -> colors.statusInfoForeground
        PrivilegeAdbPairingStatus.NOT_PAIRED -> colors.statusNeutralForeground
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
                text = "Pairing: ${status.label}",
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
                color = colors.textSecondary,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        )
        BasicText(
            text = "Fingerprint: " + when {
                fingerprintLoading -> "loading..."
                fingerprint != null -> fingerprint
                else -> "not loaded"
            },
            style = TextStyle(
                color = colors.textSecondary,
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
    val colors = sampleColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PairingStatusPanel(
            status = state.pairingStatus,
            message = state.pairingMessage,
            fingerprint = state.adbKeyFingerprint,
            fingerprintLoading = state.adbKeyFingerprintLoading,
        )
        SampleField("ADB device name (blank = app name)", state.adbDeviceNameText, onAdbDeviceNameChanged)
        RuntimeInfoRow(label = "adb name", value = state.adbDeviceName)
        RuntimeInfoRow(label = "key source", value = "owner-token")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = "Refresh Identity",
                enabled = !state.busy && !state.adbKeyFingerprintLoading,
                background = colors.actionMuted,
                modifier = Modifier.weight(1f),
                onClick = onRefreshAdbFingerprint,
            )
            SampleAction(
                label = "Check Pairing",
                enabled = !state.busy && !state.adbKeyFingerprintLoading,
                background = colors.actionAccent,
                modifier = Modifier.weight(1f),
                onClick = onCheckAdbPairing,
            )
        }
        SampleAction(
            label = "Copy Wireless Log",
            enabled = true,
            background = colors.actionLog,
            onClick = onCopyLog,
        )
        SampleField(
            label = "Pairing code",
            value = state.pairingCode,
            onValueChange = onPairingCodeChanged,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        RuntimeInfoRow(label = "connect port", value = state.connectPortText.ifBlank { "auto" })
        SampleAction(
            label = "Pair by Code",
            enabled = !state.busy,
            background = colors.actionPrimary,
            onClick = onPairWirelessAdb,
        )
        SampleAction(
            label = if (notificationPairingRunning) {
                "Stop Notification Pairing"
            } else {
                "Pair via Notification"
            },
            enabled = !state.busy || notificationPairingRunning,
            background = if (notificationPairingRunning) colors.actionDanger else colors.actionPrimary,
            onClick = if (notificationPairingRunning) onStopNotificationPairing else onStartNotificationPairing,
        )
        SampleAction(
            label = "Start Wireless ADB",
            enabled = !state.busy,
            background = colors.actionPrimary,
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
    val colors = sampleColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RuntimeInfoRow(label = "last connect port", value = state.connectPortText.ifBlank { "auto" })
        SampleField("TCP port", state.tcpPortText, onTcpPortChanged)
        SampleAction(
            label = "Switch to TCP Mode",
            enabled = !state.busy,
            background = colors.actionAccent,
            onClick = onSwitchToTcp,
        )
        SampleAction(
            label = "Restart From TCP Port",
            enabled = !state.busy,
            background = colors.actionPrimary,
            onClick = onRestartTcp,
        )
        SampleAction(
            label = "Stop TCP Mode",
            enabled = !state.busy,
            background = colors.actionNeutral,
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
    val colors = sampleColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = "Copy Log",
                enabled = state.logText.isNotBlank(),
                background = colors.actionPrimary,
                modifier = Modifier.weight(1f),
                onClick = onCopyLog,
            )
            SampleAction(
                label = "Clear Log",
                enabled = !state.busy,
                background = colors.actionNeutral,
                modifier = Modifier.weight(1f),
                onClick = onClearLog,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.terminalBackground)
                .padding(16.dp),
        ) {
            SelectionContainer {
                BasicText(
                    text = state.logText.ifBlank { "-" },
                    style = TextStyle(
                        color = colors.terminalText,
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
    val colors = sampleColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.terminalBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = "Run starter inside adb shell",
            style = TextStyle(
                color = colors.terminalMutedText,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        BasicText(
            text = commandLine,
            style = TextStyle(
                color = colors.terminalText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
        )
        SampleAction(
            label = "Copy Command",
            enabled = true,
            background = colors.actionLog,
            onClick = onCopy,
        )
    }
}
