package priv.kit.sample

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import priv.kit.core.PrivilegeServerInfo
import priv.kit.runtime.PrivilegeManualShellConnection
import priv.kit.runtime.PrivilegeRuntime
import priv.kit.runtime.PrivilegeSession
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var session: PrivilegeSession? = null
    private var manualShellConnection: PrivilegeManualShellConnection? = null
    private var screenState by mutableStateOf(PrivilegeSampleScreenState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PrivilegeSampleScreen(
                state = screenState,
                onStartRootRuntime = ::startRootRuntime,
                onPrepareManualShell = ::prepareManualShell,
                onCopyManualCommand = ::copyManualShellCommand,
            )
        }
    }

    private fun startRootRuntime() {
        if (screenState.status.isBusy()) {
            return
        }

        manualShellConnection?.cancel()
        manualShellConnection = null
        screenState = PrivilegeSampleScreenState(
            status = PrivilegeSampleStatus.STARTING,
            message = "Starting Root Runtime...",
        )

        executor.execute {
            try {
                val newSession = PrivilegeRuntime.create(applicationContext).startRoot()
                runOnUiThread {
                    connectSession(newSession, commandLine = null)
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    screenState = PrivilegeSampleScreenState(
                        status = PrivilegeSampleStatus.DISCONNECTED,
                        message = throwable.message ?: throwable.javaClass.name,
                    )
                }
            }
        }
    }

    private fun prepareManualShell() {
        if (screenState.status.isBusy()) {
            return
        }

        val connection = PrivilegeRuntime.create(applicationContext).prepareManualShell()
        manualShellConnection = connection
        screenState = PrivilegeSampleScreenState(
            status = PrivilegeSampleStatus.WAITING,
            manualShellCommandLine = connection.command.commandLine,
            message = "Run this command inside adb shell. Waiting for Binder...",
        )

        executor.execute {
            try {
                val newSession = connection.awaitSession()
                runOnUiThread {
                    if (manualShellConnection === connection) {
                        connectSession(
                            session = newSession,
                            commandLine = connection.command.commandLine,
                        )
                    }
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    if (manualShellConnection === connection) {
                        connection.cancel()
                        manualShellConnection = null
                        screenState = screenState.copy(
                            status = PrivilegeSampleStatus.DISCONNECTED,
                            serverInfo = null,
                            message = throwable.message ?: throwable.javaClass.name,
                        )
                    }
                }
            }
        }
    }

    private fun connectSession(
        session: PrivilegeSession,
        commandLine: String?,
    ) {
        this.session?.setOnDisconnectedListener(null)
        this.session = session
        manualShellConnection = null
        session.setOnDisconnectedListener {
            runOnUiThread {
                if (this.session === it) {
                    screenState = screenState.copy(
                        status = PrivilegeSampleStatus.DISCONNECTED,
                        serverInfo = null,
                        message = "Binder died",
                    )
                }
            }
        }
        screenState = PrivilegeSampleScreenState(
            status = PrivilegeSampleStatus.CONNECTED,
            serverInfo = session.serverInfo,
            manualShellCommandLine = commandLine,
            message = "Connected",
        )
    }

    private fun copyManualShellCommand() {
        val commandLine = screenState.manualShellCommandLine ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Priv Kit manual shell command", commandLine),
        )
        screenState = screenState.copy(message = "Manual shell command copied")
    }

    override fun onDestroy() {
        manualShellConnection?.cancel()
        manualShellConnection = null
        executor.shutdownNow()
        session?.setOnDisconnectedListener(null)
        session = null
        super.onDestroy()
    }
}

private enum class PrivilegeSampleStatus {
    CONNECTED,
    DISCONNECTED,
    WAITING,
    STARTING,
}

private fun PrivilegeSampleStatus.isBusy(): Boolean =
    this == PrivilegeSampleStatus.STARTING || this == PrivilegeSampleStatus.WAITING

private data class PrivilegeSampleScreenState(
    val status: PrivilegeSampleStatus = PrivilegeSampleStatus.DISCONNECTED,
    val serverInfo: PrivilegeServerInfo? = null,
    val manualShellCommandLine: String? = null,
    val message: String = "Ready",
)

@Composable
private fun PrivilegeSampleScreen(
    state: PrivilegeSampleScreenState,
    onStartRootRuntime: () -> Unit,
    onPrepareManualShell: () -> Unit,
    onCopyManualCommand: () -> Unit,
) {
    val isBusy = state.status.isBusy()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F7F9))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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

        SampleAction(
            label = if (state.status == PrivilegeSampleStatus.STARTING) {
                "Starting Root Runtime..."
            } else {
                "Start Root Runtime"
            },
            enabled = !isBusy,
            background = Color(0xFF1769E0),
            onClick = onStartRootRuntime,
        )

        SampleAction(
            label = if (state.status == PrivilegeSampleStatus.WAITING) {
                "Waiting for Manual Shell..."
            } else {
                "Prepare Manual Shell Command"
            },
            enabled = !isBusy,
            background = Color(0xFF2F5D50),
            onClick = onPrepareManualShell,
        )

        StatusPill(status = state.status)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RuntimeInfoRow(label = "uid", value = state.serverInfo?.uid?.toString() ?: "-")
            RuntimeInfoRow(label = "pid", value = state.serverInfo?.pid?.toString() ?: "-")
            RuntimeInfoRow(label = "mode", value = state.serverInfo?.mode?.toString() ?: "-")
            RuntimeInfoRow(
                label = "protocolVersion",
                value = state.serverInfo?.protocolVersion?.toString() ?: "-",
            )
            RuntimeInfoRow(
                label = "serverVersion",
                value = state.serverInfo?.serverVersion ?: "-",
            )
        }

        state.manualShellCommandLine?.let { commandLine ->
            ManualShellCommandBlock(
                commandLine = commandLine,
                onCopy = onCopyManualCommand,
            )
        }

        BasicText(
            text = state.message,
            style = TextStyle(
                color = Color(0xFF48525C),
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
            ),
        )
    }
}

@Composable
private fun SampleAction(
    label: String,
    enabled: Boolean,
    background: Color,
    onClick: () -> Unit,
) {
    val actualBackground = if (enabled) background else Color(0xFF9DA8B5)

    BasicText(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(actualBackground)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        style = TextStyle(
            color = Color.White,
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

@Composable
private fun StatusPill(status: PrivilegeSampleStatus) {
    val isConnected = status == PrivilegeSampleStatus.CONNECTED
    val text = when (status) {
        PrivilegeSampleStatus.CONNECTED -> "Connected"
        PrivilegeSampleStatus.DISCONNECTED -> "Disconnected"
        PrivilegeSampleStatus.WAITING -> "Waiting"
        PrivilegeSampleStatus.STARTING -> "Starting"
    }
    val background = when (status) {
        PrivilegeSampleStatus.CONNECTED -> Color(0xFFE4F4EA)
        PrivilegeSampleStatus.DISCONNECTED -> Color(0xFFF1F3F5)
        PrivilegeSampleStatus.WAITING -> Color(0xFFFFF4D9)
        PrivilegeSampleStatus.STARTING -> Color(0xFFEAF1FF)
    }
    val foreground = when {
        isConnected -> Color(0xFF16743A)
        status == PrivilegeSampleStatus.WAITING -> Color(0xFF916300)
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
private fun ManualShellCommandBlock(
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
            text = "Run inside adb shell",
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
        BasicText(
            text = "Copy command",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A3541))
                .clickable(
                    role = Role.Button,
                    onClick = onCopy,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            style = TextStyle(
                color = Color.White,
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
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
