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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.core.PrivilegeServerInfo
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiViewModel

internal sealed interface PrivilegeSampleDestination {
    val title: String

    data object Connection : PrivilegeSampleDestination {
        override val title: String = "Test Authorization"
    }

    data object Binder : PrivilegeSampleDestination {
        override val title: String = "Test Binder"
    }

    data object UserService : PrivilegeSampleDestination {
        override val title: String = "Test UserService"
    }

    data object PrivilegeUi : PrivilegeSampleDestination {
        override val title: String = "Authorization UI"
    }

    companion object {
        val entries: List<PrivilegeSampleDestination> = listOf(Connection, Binder, UserService)
    }
}

internal sealed interface PrivilegeStartupTab {
    val title: String

    data object Root : PrivilegeStartupTab {
        override val title: String = "Root"
    }

    data object Manual : PrivilegeStartupTab {
        override val title: String = "Manual"
    }

    data object Shizuku : PrivilegeStartupTab {
        override val title: String = "Shizuku"
    }

    data object WirelessAdb : PrivilegeStartupTab {
        override val title: String = "Wireless"
    }

    data object Tcp : PrivilegeStartupTab {
        override val title: String = "TCP"
    }

    data object Log : PrivilegeStartupTab {
        override val title: String = "Log"
    }

    companion object {
        val entries: List<PrivilegeStartupTab> = listOf(
            Root,
            Manual,
            Shizuku,
            WirelessAdb,
            Tcp,
            Log,
        )
    }
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
    val pairingMessage: String = "Enter the Wireless debugging pairing code, or reply from the pairing notification.",
    val tcpPortText: String = PrivilegeAdbStartOptions.DEFAULT_TCP_PORT.toString(),
    val systemServiceBinderCached: Boolean = false,
    val userManagerCached: Boolean = false,
    val mqsNativeLocalDescriptor: String? = null,
    val mqsNativeLocalError: String? = null,
    val mqsNativeRemoteDescriptor: String? = null,
    val mqsNativeRemoteError: String? = null,
    val binderMessage: String = "Connect to a Privileged Server, then get IUserManager or probe IMQSNative.",
    val binderLastException: String = "",
    val dedicatedUserServiceBound: Boolean = false,
    val embeddedUserServiceBound: Boolean = false,
    val dedicatedUserServiceCached: Boolean = false,
    val embeddedUserServiceCached: Boolean = false,
    val dedicatedUserServiceMessage: String = "-",
    val embeddedUserServiceMessage: String = "-",
    val userServiceMessage: String = "Connect to a Privileged Server, then bind a UserService.",
    val userServiceLastException: String = "",
    val shizukuReady: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val shizukuUid: Int? = null,
    val shizukuVersion: Int? = null,
    val shizukuMessage: String = "Shizuku status not checked",
    val shizukuLastException: String = "",
    val message: String = "Ready",
    val logText: String = "",
)

internal fun PrivilegeSampleScreenState.wirelessDebugLogText(): String =
    buildString {
        appendLine("Priv Kit Wireless ADB diagnostics")
        appendLine("busy=$busy")
        appendLine("runtimeStatus=$status")
        appendLine("message=$message")
        appendLine("pairingStatus=$pairingStatus")
        appendLine("pairingMessage=$pairingMessage")
        appendLine("adbDeviceNameConfigured=${adbDeviceNameText.ifBlank { "app-name" }}")
        appendLine("adbDeviceName=$adbDeviceName")
        appendLine("adbKeySource=owner-token")
        appendLine("adbKeyFingerprint=${adbKeyFingerprint ?: "not loaded"}")
        appendLine("pairingPort=${pairingPortText.ifBlank { "auto" }}")
        appendLine("connectPort=${connectPortText.ifBlank { "auto" }}")
        appendLine("tcpPort=${tcpPortText.ifBlank { "blank" }}")
        appendLine("systemServiceBinderCached=$systemServiceBinderCached")
        appendLine("userManagerCached=$userManagerCached")
        appendLine("mqsNativeLocal=${mqsNativeLocalDescriptor ?: mqsNativeLocalError ?: "none"}")
        appendLine("mqsNativeRemote=${mqsNativeRemoteDescriptor ?: mqsNativeRemoteError ?: "none"}")
        appendLine("binderMessage=$binderMessage")
        appendLine("dedicatedUserServiceBound=$dedicatedUserServiceBound")
        appendLine("embeddedUserServiceBound=$embeddedUserServiceBound")
        appendLine("dedicatedUserServiceCached=$dedicatedUserServiceCached")
        appendLine("embeddedUserServiceCached=$embeddedUserServiceCached")
        appendLine("userServiceMessage=$userServiceMessage")
        appendLine("shizukuReady=$shizukuReady")
        appendLine("shizukuPermissionGranted=$shizukuPermissionGranted")
        appendLine("shizukuUid=${shizukuUid ?: "none"}")
        appendLine("shizukuVersion=${shizukuVersion ?: "none"}")
        appendLine("shizukuMessage=$shizukuMessage")
        appendLine("serverInfo=${serverInfo ?: "none"}")
        appendLine()
        appendLine("Session log:")
        appendLine(logText.ifBlank { "<empty>" })
    }

@Composable
internal fun PrivilegeSampleScreen(
    state: PrivilegeSampleScreenState,
    backStack: SnapshotStateList<PrivilegeSampleDestination>,
    selectedStartupTab: PrivilegeStartupTab,
    privilegeUiConfig: PrivilegeUiConfig,
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
    onStartShizukuDelegate: () -> Unit,
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
    MaterialTheme {
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
                        onStartShizukuDelegate = onStartShizukuDelegate,
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
                        config = privilegeUiConfig,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SamplePageScaffold(
    title: String,
    selectedDestination: PrivilegeSampleDestination,
    busy: Boolean,
    onDestinationSelected: (PrivilegeSampleDestination) -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = Color(0xFFF6F7F9),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF6F7F9)),
            ) {
                TopAppBar(
                    title = {
                        BasicText(
                            text = title,
                            style = TextStyle(
                                color = Color(0xFF101418),
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    },
                    actions = {
                        actions()
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFFF6F7F9),
                    ),
                )
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                ) {
                    DestinationTabs(
                        selectedDestination = selectedDestination,
                        busy = busy,
                        onDestinationSelected = onDestinationSelected,
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun DestinationTabs(
    selectedDestination: PrivilegeSampleDestination,
    busy: Boolean,
    onDestinationSelected: (PrivilegeSampleDestination) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrivilegeSampleDestination.entries.forEach { destination ->
            val selected = destination == selectedDestination
            SampleAction(
                label = destination.title,
                enabled = !busy || selected,
                background = if (selected) Color(0xFF1769E0) else Color(0xFFE2E7EE),
                foreground = if (selected) Color.White else Color(0xFF27313B),
                modifier = Modifier.weight(1f),
            ) {
                onDestinationSelected(destination)
            }
        }
    }
}

@Composable
internal fun SampleTopBarAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = if (enabled) Color(0xFF1769E0) else Color(0xFF9DA8B5)
    Box(
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = 104.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
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
                color = Color.White,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun ConnectionTestPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDestination,
    selectedStartupTab: PrivilegeStartupTab,
    notificationPairingRunning: Boolean,
    onDestinationSelected: (PrivilegeSampleDestination) -> Unit,
    onStartupTabSelected: (PrivilegeStartupTab) -> Unit,
    onOpenPrivilegeUi: () -> Unit,
    onAdbDeviceNameChanged: (String) -> Unit,
    onRefreshAdbFingerprint: () -> Unit,
    onCheckAdbPairing: () -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onTcpPortChanged: (String) -> Unit,
    onStartRootRuntime: () -> Unit,
    onCopyManualCommand: () -> Unit,
    onStartShizukuDelegate: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onStartNotificationPairing: () -> Unit,
    onStopNotificationPairing: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onSwitchToTcp: () -> Unit,
    onRestartTcp: () -> Unit,
    onStopTcp: () -> Unit,
    onStopServer: () -> Unit,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
) {
    SamplePageScaffold(
        title = "Test Authorization",
        selectedDestination = selectedDestination,
        busy = state.busy,
        onDestinationSelected = onDestinationSelected,
        actions = {
            SampleTopBarAction(
                label = stringResource(R.string.sample_open_privilege_ui),
                enabled = !state.busy,
                onClick = onOpenPrivilegeUi,
            )
        },
    ) {
        StatusPanel(state, onStopServer)
        StartupTabs(
            selectedStartupTab = selectedStartupTab,
            busy = state.busy,
            onStartupTabSelected = onStartupTabSelected,
        )
        when (selectedStartupTab) {
            PrivilegeStartupTab.Root -> RootPage(state, onStartRootRuntime)
            PrivilegeStartupTab.Manual -> ManualPage(state, onCopyManualCommand)
            PrivilegeStartupTab.Shizuku -> ShizukuDelegatePage(
                state = state,
                onStartShizukuDelegate = onStartShizukuDelegate,
            )
            PrivilegeStartupTab.WirelessAdb -> WirelessAdbPage(
                state = state,
                notificationPairingRunning = notificationPairingRunning,
                onAdbDeviceNameChanged = onAdbDeviceNameChanged,
                onRefreshAdbFingerprint = onRefreshAdbFingerprint,
                onCheckAdbPairing = onCheckAdbPairing,
                onPairingCodeChanged = onPairingCodeChanged,
                onCopyLog = onCopyLog,
                onPairWirelessAdb = onPairWirelessAdb,
                onStartNotificationPairing = onStartNotificationPairing,
                onStopNotificationPairing = onStopNotificationPairing,
                onStartWirelessAdb = onStartWirelessAdb,
            )
            PrivilegeStartupTab.Tcp -> TcpPage(
                state = state,
                onTcpPortChanged = onTcpPortChanged,
                onSwitchToTcp = onSwitchToTcp,
                onRestartTcp = onRestartTcp,
                onStopTcp = onStopTcp,
            )
            PrivilegeStartupTab.Log -> SessionPage(state, onClearLog, onCopyLog)
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
    val background = when {
        selected -> Color(0xFF1769E0)
        enabled -> Color(0xFFE2E7EE)
        else -> Color(0xFFCAD3DD)
    }
    val foreground = if (selected) Color.White else Color(0xFF27313B)
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
private fun BinderTestPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDestination,
    onDestinationSelected: (PrivilegeSampleDestination) -> Unit,
    onGetUserManager: () -> Unit,
    onGetUsers: () -> Unit,
    onRunImqsNative: () -> Unit,
    onStopServer: () -> Unit,
) {
    SamplePageScaffold(
        title = "Test Binder",
        selectedDestination = selectedDestination,
        busy = state.busy,
        onDestinationSelected = onDestinationSelected,
    ) {
        StatusPanel(state, onStopServer)
        BinderPage(
            state = state,
            onGetUserManager = onGetUserManager,
            onGetUsers = onGetUsers,
            onRunImqsNative = onRunImqsNative,
        )
    }
}

@Composable
private fun UserServiceTestPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDestination,
    onDestinationSelected: (PrivilegeSampleDestination) -> Unit,
    onBindDedicatedUserService: () -> Unit,
    onCallDedicatedUserService: () -> Unit,
    onStopDedicatedUserService: () -> Unit,
    onBindEmbeddedUserService: () -> Unit,
    onCallEmbeddedUserService: () -> Unit,
    onStopEmbeddedUserService: () -> Unit,
    onStopServer: () -> Unit,
) {
    SamplePageScaffold(
        title = "Test UserService",
        selectedDestination = selectedDestination,
        busy = state.busy,
        onDestinationSelected = onDestinationSelected,
    ) {
        StatusPanel(state, onStopServer)
        UserServicePage(
            state = state,
            onBindDedicatedUserService = onBindDedicatedUserService,
            onCallDedicatedUserService = onCallDedicatedUserService,
            onStopDedicatedUserService = onStopDedicatedUserService,
            onBindEmbeddedUserService = onBindEmbeddedUserService,
            onCallEmbeddedUserService = onCallEmbeddedUserService,
            onStopEmbeddedUserService = onStopEmbeddedUserService,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = Color(0xFF27313B),
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

@Composable
private fun BinderPage(
    state: PrivilegeSampleScreenState,
    onGetUserManager: () -> Unit,
    onGetUsers: () -> Unit,
    onRunImqsNative: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BinderStatusPanel(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = if (state.userManagerCached) "IUserManager Cached" else "Get IUserManager",
                enabled = !state.busy &&
                    state.status == PrivilegeSampleStatus.CONNECTED &&
                    !state.userManagerCached,
                background = Color(0xFF7A4E1D),
                modifier = Modifier.weight(1f),
                onClick = onGetUserManager,
            )
            SampleAction(
                label = "Get Users",
                enabled = !state.busy &&
                    (state.status == PrivilegeSampleStatus.CONNECTED || state.userManagerCached),
                background = Color(0xFF087443),
                modifier = Modifier.weight(1f),
                onClick = onGetUsers,
            )
        }
        SampleAction(
            label = "Probe IMQSNative",
            enabled = !state.busy &&
                (state.status == PrivilegeSampleStatus.CONNECTED || state.systemServiceBinderCached),
            background = Color(0xFF5E4FA2),
            modifier = Modifier.fillMaxWidth(),
            onClick = onRunImqsNative,
        )
        if (state.binderLastException.isNotBlank()) {
            DiagnosticBlock(state.binderLastException)
        }
    }
}

@Composable
private fun BinderStatusPanel(state: PrivilegeSampleScreenState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RuntimeInfoRow(label = "IMQSNative", value = if (state.systemServiceBinderCached) "cached" else "-")
        RuntimeInfoRow(
            label = "IMQS Local",
            value = state.mqsNativeLocalDescriptor ?: state.mqsNativeLocalError ?: "-",
        )
        RuntimeInfoRow(
            label = "IMQS Remote",
            value = state.mqsNativeRemoteDescriptor ?: state.mqsNativeRemoteError ?: "-",
        )
        RuntimeInfoRow(label = "IUserManager", value = if (state.userManagerCached) "cached" else "-")
        SelectionContainer {
            BasicText(
                text = state.binderMessage,
                style = TextStyle(
                    color = Color(0xFF48525C),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

@Composable
private fun UserServicePage(
    state: PrivilegeSampleScreenState,
    onBindDedicatedUserService: () -> Unit,
    onCallDedicatedUserService: () -> Unit,
    onStopDedicatedUserService: () -> Unit,
    onBindEmbeddedUserService: () -> Unit,
    onCallEmbeddedUserService: () -> Unit,
    onStopEmbeddedUserService: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UserServiceStatusPanel(state)
        SectionTitle("Dedicated Process")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = if (state.dedicatedUserServiceBound) "Dedicated Bound" else "Bind Dedicated",
                enabled = !state.busy &&
                    state.status == PrivilegeSampleStatus.CONNECTED &&
                    !state.dedicatedUserServiceBound,
                background = Color(0xFF1769E0),
                modifier = Modifier.weight(1f),
                onClick = onBindDedicatedUserService,
            )
            SampleAction(
                label = "Call",
                enabled = !state.busy && state.dedicatedUserServiceCached,
                background = Color(0xFF087443),
                modifier = Modifier.weight(1f),
                onClick = onCallDedicatedUserService,
            )
            SampleAction(
                label = "Stop",
                enabled = !state.busy && state.dedicatedUserServiceCached,
                background = Color(0xFFB42318),
                modifier = Modifier.weight(1f),
                onClick = onStopDedicatedUserService,
            )
        }
        SectionTitle("Embedded")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = if (state.embeddedUserServiceBound) "Embedded Bound" else "Bind Embedded",
                enabled = !state.busy &&
                    state.status == PrivilegeSampleStatus.CONNECTED &&
                    !state.embeddedUserServiceBound,
                background = Color(0xFF5E4FA2),
                modifier = Modifier.weight(1f),
                onClick = onBindEmbeddedUserService,
            )
            SampleAction(
                label = "Call",
                enabled = !state.busy && state.embeddedUserServiceCached,
                background = Color(0xFF087443),
                modifier = Modifier.weight(1f),
                onClick = onCallEmbeddedUserService,
            )
            SampleAction(
                label = "Stop",
                enabled = !state.busy && state.embeddedUserServiceCached,
                background = Color(0xFFB42318),
                modifier = Modifier.weight(1f),
                onClick = onStopEmbeddedUserService,
            )
        }
        if (state.userServiceLastException.isNotBlank()) {
            DiagnosticBlock(state.userServiceLastException)
        }
    }
}

@Composable
private fun UserServiceStatusPanel(state: PrivilegeSampleScreenState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RuntimeInfoRow(
            label = "dedicated",
            value = state.userServiceReferenceText(
                bound = state.dedicatedUserServiceBound,
                cached = state.dedicatedUserServiceCached,
            ),
        )
        RuntimeInfoRow(
            label = "embedded",
            value = state.userServiceReferenceText(
                bound = state.embeddedUserServiceBound,
                cached = state.embeddedUserServiceCached,
            ),
        )
        SelectionContainer {
            BasicText(
                text = buildString {
                    appendLine(state.userServiceMessage)
                    appendLine("dedicated: ${state.dedicatedUserServiceMessage}")
                    append("embedded: ${state.embeddedUserServiceMessage}")
                },
                style = TextStyle(
                    color = Color(0xFF48525C),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

private fun PrivilegeSampleScreenState.userServiceReferenceText(
    bound: Boolean,
    cached: Boolean,
): String =
    when {
        bound -> "bound"
        cached -> "cached"
        else -> "-"
    }

@Composable
private fun DiagnosticBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111820))
            .padding(16.dp),
    ) {
        SelectionContainer {
            BasicText(
                text = text,
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
            Spacer(Modifier.width(12.dp))
            BasicText(
                modifier = Modifier.weight(1f),
                text = state.message,
                style = TextStyle(
                    color = Color(0xFF48525C),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                    textAlign = TextAlign.End,
                ),
            )
        }
        RuntimeInfoRow(label = "uid", value = state.serverInfo?.uid?.toString() ?: "-")
        RuntimeInfoRow(label = "pid", value = state.serverInfo?.pid?.toString() ?: "-")
        RuntimeInfoRow(label = "launchMode", value = state.serverInfo?.launchMode?.toString() ?: "-")
        RuntimeInfoRow(label = "protocol", value = state.serverInfo?.protocolVersion?.toString() ?: "-")
        RuntimeInfoRow(label = "serverVersion", value = state.serverInfo?.serverVersion ?: "-")
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
private fun ShizukuDelegatePage(
    state: PrivilegeSampleScreenState,
    onStartShizukuDelegate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
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
                        color = Color(0xFF48525C),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                )
            }
        }
        SampleAction(
            label = state.shizukuDelegateActionLabel(),
            enabled = !state.busy,
            background = Color(0xFF1769E0),
            onClick = onStartShizukuDelegate,
        )
        if (state.shizukuLastException.isNotBlank()) {
            DiagnosticBlock(state.shizukuLastException)
        }
    }
}

private fun PrivilegeSampleScreenState.shizukuDelegateActionLabel(): String =
    if (shizukuReady && shizukuPermissionGranted) {
        "Start Delegate"
    } else {
        "Authorize and Start Delegate"
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
            background = Color(0xFF1769E0),
            onClick = onPairWirelessAdb,
        )
        SampleAction(
            label = if (notificationPairingRunning) {
                "Stop Notification Pairing"
            } else {
                "Pair via Notification"
            },
            enabled = !state.busy || notificationPairingRunning,
            background = if (notificationPairingRunning) Color(0xFFB42318) else Color(0xFF1769E0),
            onClick = if (notificationPairingRunning) onStopNotificationPairing else onStartNotificationPairing,
        )
        SampleAction(
            label = "Start Wireless ADB",
            enabled = !state.busy,
            background = Color(0xFF1769E0),
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
        RuntimeInfoRow(label = "last connect port", value = state.connectPortText.ifBlank { "auto" })
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
            SelectionContainer {
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
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
            keyboardOptions = keyboardOptions,
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
