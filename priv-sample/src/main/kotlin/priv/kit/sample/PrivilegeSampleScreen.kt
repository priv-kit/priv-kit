package priv.kit.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.core.PrivilegeServerInfo

internal enum class PrivilegeSamplePage(val title: String) {
    ROOT("Root"),
    MANUAL("Manual"),
    ADB("Wireless ADB"),
    TCP("TCP"),
    SESSION("Session"),
}

internal enum class PrivilegeSampleStatus {
    CONNECTED,
    DISCONNECTED,
    STARTING,
}

internal enum class PrivilegeAdbPairingStatus(val label: String) {
    NOT_PAIRED("Not paired"),
    CHECKING("Checking"),
    SEARCHING("Searching"),
    FOUND("Port found"),
    PAIRING("Pairing"),
    PAIRED("Paired"),
    FAILED("Failed"),
}

internal data class PrivilegeSampleScreenState(
    val page: PrivilegeSamplePage = PrivilegeSamplePage.ROOT,
    val busy: Boolean = false,
    val status: PrivilegeSampleStatus = PrivilegeSampleStatus.DISCONNECTED,
    val serverInfo: PrivilegeServerInfo? = null,
    val manualShellCommandLine: String? = null,
    val adbDeviceNameText: String = "",
    val adbDeviceName: String = "",
    val adbKeyFingerprint: String? = null,
    val adbKeyFingerprintLoading: Boolean = false,
    val pairingPortText: String = "",
    val connectPortText: String = "",
    val pairingCode: String = "",
    val pairingStatus: PrivilegeAdbPairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
    val pairingMessage: String = "Enter the pairing code shown by Wireless debugging.",
    val tcpPortText: String = PrivilegeAdbStartOptions.DEFAULT_TCP_PORT.toString(),
    val message: String = "Ready",
    val logText: String = "",
)

internal fun PrivilegeSampleScreenState.wirelessDebugLogText(): String =
    buildString {
        appendLine("Priv Kit Wireless ADB diagnostics")
        appendLine("page=$page")
        appendLine("busy=$busy")
        appendLine("runtimeStatus=$status")
        appendLine("message=$message")
        appendLine("pairingStatus=$pairingStatus")
        appendLine("pairingMessage=$pairingMessage")
        appendLine("adbDeviceNameConfigured=${adbDeviceNameText.ifBlank { "app-name" }}")
        appendLine("adbDeviceName=$adbDeviceName")
        appendLine("adbKeySource=owner-token")
        appendLine("adbKeyFingerprint=${adbKeyFingerprint ?: "not loaded"}")
        appendLine("pairingPort=${pairingPortText.ifBlank { "blank" }}")
        appendLine("connectPort=${connectPortText.ifBlank { "blank" }}")
        appendLine("tcpPort=${tcpPortText.ifBlank { "blank" }}")
        appendLine("serverInfo=${serverInfo ?: "none"}")
        appendLine()
        appendLine("Session log:")
        appendLine(logText.ifBlank { "<empty>" })
    }

@Composable
internal fun PrivilegeSampleScreen(
    state: PrivilegeSampleScreenState,
    onPageSelected: (PrivilegeSamplePage) -> Unit,
    onAdbDeviceNameChanged: (String) -> Unit,
    onPairingPortChanged: (String) -> Unit,
    onConnectPortChanged: (String) -> Unit,
    onRefreshAdbFingerprint: () -> Unit,
    onCheckAdbPairing: () -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onTcpPortChanged: (String) -> Unit,
    onStartRootRuntime: () -> Unit,
    onCopyManualCommand: () -> Unit,
    onDiscoverPairingPort: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onDiscoverConnectPort: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onSwitchToTcp: () -> Unit,
    onRestartTcp: () -> Unit,
    onStopTcp: () -> Unit,
    onStopServer: () -> Unit,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F7F9))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText(
            text = "Priv Kit Sample",
            style = TextStyle(
                color = Color(0xFF101418),
                fontFamily = FontFamily.SansSerif,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )

        PageTabs(
            selectedPage = state.page,
            busy = state.busy,
            onPageSelected = onPageSelected,
        )

        StatusPanel(state, onStopServer)

        when (state.page) {
            PrivilegeSamplePage.ROOT -> RootPage(state, onStartRootRuntime)
            PrivilegeSamplePage.MANUAL -> ManualPage(
                state = state,
                onCopyManualCommand = onCopyManualCommand,
            )
            PrivilegeSamplePage.ADB -> WirelessAdbPage(
                state = state,
                onAdbDeviceNameChanged = onAdbDeviceNameChanged,
                onPairingPortChanged = onPairingPortChanged,
                onConnectPortChanged = onConnectPortChanged,
                onRefreshAdbFingerprint = onRefreshAdbFingerprint,
                onCheckAdbPairing = onCheckAdbPairing,
                onPairingCodeChanged = onPairingCodeChanged,
                onCopyLog = onCopyLog,
                onDiscoverPairingPort = onDiscoverPairingPort,
                onPairWirelessAdb = onPairWirelessAdb,
                onDiscoverConnectPort = onDiscoverConnectPort,
                onStartWirelessAdb = onStartWirelessAdb,
            )
            PrivilegeSamplePage.TCP -> TcpPage(
                state = state,
                onTcpPortChanged = onTcpPortChanged,
                onConnectPortChanged = onConnectPortChanged,
                onSwitchToTcp = onSwitchToTcp,
                onRestartTcp = onRestartTcp,
                onStopTcp = onStopTcp,
            )
            PrivilegeSamplePage.SESSION -> SessionPage(state, onClearLog, onCopyLog)
        }
    }
}

@Composable
private fun PageTabs(
    selectedPage: PrivilegeSamplePage,
    busy: Boolean,
    onPageSelected: (PrivilegeSamplePage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrivilegeSamplePage.entries.chunked(2).forEach { rowPages ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPages.forEach { page ->
                    val selected = page == selectedPage
                    SampleAction(
                        label = page.title,
                        enabled = !busy || selected,
                        background = if (selected) Color(0xFF1769E0) else Color(0xFFE2E7EE),
                        foreground = if (selected) Color.White else Color(0xFF27313B),
                        modifier = Modifier.weight(1f),
                    ) {
                        onPageSelected(page)
                    }
                }
                if (rowPages.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusPanel(
    state: PrivilegeSampleScreenState,
    onStopServer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(state.status, state.busy)
            BasicText(
                text = state.message,
                style = TextStyle(
                    color = Color(0xFF48525C),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                ),
            )
        }
        RuntimeInfoRow(label = "uid", value = state.serverInfo?.uid?.toString() ?: "-")
        RuntimeInfoRow(label = "pid", value = state.serverInfo?.pid?.toString() ?: "-")
        RuntimeInfoRow(label = "mode", value = state.serverInfo?.mode?.toString() ?: "-")
        RuntimeInfoRow(label = "protocol", value = state.serverInfo?.protocolVersion?.toString() ?: "-")
        RuntimeInfoRow(label = "server", value = state.serverInfo?.serverVersion ?: "-")
        SampleAction(
            label = "Stop Server",
            enabled = !state.busy && state.status == PrivilegeSampleStatus.CONNECTED,
            background = Color(0xFFB42318),
            onClick = onStopServer,
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
                "Starting Root Runtime..."
            } else {
                "Start Root Runtime"
            },
            enabled = !state.busy,
            background = Color(0xFF1769E0),
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
                label = "Manual Shell Command Unavailable",
                enabled = false,
                background = Color(0xFF5E6873),
                onClick = {},
            )
        }
    }
}

@Composable
private fun PairingStatusPanel(
    status: PrivilegeAdbPairingStatus,
    message: String,
    fingerprint: String?,
    fingerprintLoading: Boolean,
) {
    val background = when (status) {
        PrivilegeAdbPairingStatus.PAIRED -> Color(0xFFE4F4EA)
        PrivilegeAdbPairingStatus.FAILED -> Color(0xFFFFECE8)
        PrivilegeAdbPairingStatus.CHECKING,
        PrivilegeAdbPairingStatus.SEARCHING,
        PrivilegeAdbPairingStatus.PAIRING,
        PrivilegeAdbPairingStatus.FOUND,
        -> Color(0xFFEAF1FF)
        PrivilegeAdbPairingStatus.NOT_PAIRED -> Color(0xFFF1F3F5)
    }
    val foreground = when (status) {
        PrivilegeAdbPairingStatus.PAIRED -> Color(0xFF16743A)
        PrivilegeAdbPairingStatus.FAILED -> Color(0xFFB42318)
        PrivilegeAdbPairingStatus.CHECKING,
        PrivilegeAdbPairingStatus.SEARCHING,
        PrivilegeAdbPairingStatus.PAIRING,
        PrivilegeAdbPairingStatus.FOUND,
        -> Color(0xFF1769E0)
        PrivilegeAdbPairingStatus.NOT_PAIRED -> Color(0xFF48525C)
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
                color = Color(0xFF27313B),
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
                color = Color(0xFF27313B),
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
    onAdbDeviceNameChanged: (String) -> Unit,
    onPairingPortChanged: (String) -> Unit,
    onConnectPortChanged: (String) -> Unit,
    onRefreshAdbFingerprint: () -> Unit,
    onCheckAdbPairing: () -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onCopyLog: () -> Unit,
    onDiscoverPairingPort: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onDiscoverConnectPort: () -> Unit,
    onStartWirelessAdb: () -> Unit,
) {
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
                background = Color(0xFF5E6873),
                modifier = Modifier.weight(1f),
                onClick = onRefreshAdbFingerprint,
            )
            SampleAction(
                label = "Check Pairing",
                enabled = !state.busy && !state.adbKeyFingerprintLoading,
                background = Color(0xFF5E4FA2),
                modifier = Modifier.weight(1f),
                onClick = onCheckAdbPairing,
            )
        }
        SampleAction(
            label = "Copy Wireless Log",
            enabled = true,
            background = Color(0xFF2A3541),
            onClick = onCopyLog,
        )
        SampleField("Pairing code", state.pairingCode, onPairingCodeChanged)
        SampleField("Pairing port (optional)", state.pairingPortText, onPairingPortChanged)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = "Find Pairing Port",
                enabled = !state.busy,
                background = Color(0xFF5E4FA2),
                modifier = Modifier.weight(1f),
                onClick = onDiscoverPairingPort,
            )
            SampleAction(
                label = "Pair by Code",
                enabled = !state.busy,
                background = Color(0xFF1769E0),
                modifier = Modifier.weight(1f),
                onClick = onPairWirelessAdb,
            )
        }

        SampleField("Connect port", state.connectPortText, onConnectPortChanged)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = "Discover Connect Port",
                enabled = !state.busy,
                background = Color(0xFF5E6873),
                modifier = Modifier.weight(1f),
                onClick = onDiscoverConnectPort,
            )
            SampleAction(
                label = "Start Wireless ADB",
                enabled = !state.busy,
                background = Color(0xFF1769E0),
                modifier = Modifier.weight(1f),
                onClick = onStartWirelessAdb,
            )
        }
    }
}

@Composable
private fun TcpPage(
    state: PrivilegeSampleScreenState,
    onTcpPortChanged: (String) -> Unit,
    onConnectPortChanged: (String) -> Unit,
    onSwitchToTcp: () -> Unit,
    onRestartTcp: () -> Unit,
    onStopTcp: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SampleField("Current wireless port", state.connectPortText, onConnectPortChanged)
        SampleField("TCP port", state.tcpPortText, onTcpPortChanged)
        SampleAction(
            label = "Switch to TCP Mode",
            enabled = !state.busy,
            background = Color(0xFF5E4FA2),
            onClick = onSwitchToTcp,
        )
        SampleAction(
            label = "Restart From TCP Port",
            enabled = !state.busy,
            background = Color(0xFF1769E0),
            onClick = onRestartTcp,
        )
        SampleAction(
            label = "Stop TCP Mode",
            enabled = !state.busy,
            background = Color(0xFF66717D),
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = "Copy Log",
                enabled = state.logText.isNotBlank(),
                background = Color(0xFF1769E0),
                modifier = Modifier.weight(1f),
                onClick = onCopyLog,
            )
            SampleAction(
                label = "Clear Log",
                enabled = !state.busy,
                background = Color(0xFF66717D),
                modifier = Modifier.weight(1f),
                onClick = onClearLog,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111820))
                .padding(16.dp),
        ) {
            BasicText(
                text = state.logText.ifBlank { "-" },
                style = TextStyle(
                    color = Color(0xFFF7FAFC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

internal fun Throwable.toDiagnosticString(): String {
    val lines = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        lines += "Cause[$depth]: ${current.javaClass.name}: ${current.message.orEmpty()}"
        current.stackTrace.take(8).forEach { frame ->
            lines += "  at $frame"
        }
        current = current.cause
        depth++
    }
    return lines.joinToString("\n")
}

internal fun String?.toSampleAdbDeviceName(): String? {
    val value = this
        ?.replace('\u0000', ' ')
        ?.replace('\r', ' ')
        ?.replace('\n', ' ')
        ?.trim()
        ?.take(128)
    return value?.ifBlank { null }
}

@Composable
private fun SampleField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = label,
            style = TextStyle(
                color = Color(0xFF5E6873),
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = Color(0xFF101418),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SampleAction(
    label: String,
    enabled: Boolean,
    background: Color,
    modifier: Modifier = Modifier,
    foreground: Color = Color.White,
    onClick: () -> Unit,
) {
    val actualBackground = if (enabled) background else Color(0xFF9DA8B5)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(actualBackground)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = foreground,
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun StatusPill(
    status: PrivilegeSampleStatus,
    busy: Boolean,
) {
    val text = when {
        busy -> "Busy"
        status == PrivilegeSampleStatus.CONNECTED -> "Connected"
        status == PrivilegeSampleStatus.STARTING -> "Starting"
        else -> "Disconnected"
    }
    val background = when {
        busy -> Color(0xFFEAF1FF)
        status == PrivilegeSampleStatus.CONNECTED -> Color(0xFFE4F4EA)
        else -> Color(0xFFF1F3F5)
    }
    val foreground = when {
        busy -> Color(0xFF1769E0)
        status == PrivilegeSampleStatus.CONNECTED -> Color(0xFF16743A)
        else -> Color(0xFF48525C)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(foreground),
        )
        Spacer(modifier = Modifier.width(8.dp))
        BasicText(
            text = text,
            style = TextStyle(
                color = foreground,
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun CommandBlock(
    commandLine: String,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111820))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = "Run starter inside adb shell",
            style = TextStyle(
                color = Color(0xFFB8C4D0),
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        BasicText(
            text = commandLine,
            style = TextStyle(
                color = Color(0xFFF7FAFC),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
        )
        SampleAction(
            label = "Copy Command",
            enabled = true,
            background = Color(0xFF2A3541),
            onClick = onCopy,
        )
    }
}

@Composable
private fun RuntimeInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = Color(0xFF5E6873),
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
            ),
        )
        Spacer(modifier = Modifier.width(16.dp))
        BasicText(
            text = value,
            style = TextStyle(
                color = Color(0xFF101418),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
