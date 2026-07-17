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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SamplePageScaffold(
    title: String,
    selectedDestination: PrivilegeSampleDestination,
    busy: Boolean,
    onDestinationSelected: (PrivilegeSampleDestination) -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background),
            ) {
                TopAppBar(
                    title = {
                        BasicText(
                            text = title,
                            style = TextStyle(
                                color = colors.onBackground,
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
                        containerColor = colors.background,
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
private fun DestinationTabs(
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
                tone = if (selected) SampleActionTone.Primary else SampleActionTone.Neutral,
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
    val colors = MaterialTheme.colorScheme
    val background = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.12f)
    val foreground = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = 0.38f)
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
                color = foreground,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    val colors = MaterialTheme.colorScheme
    BasicText(
        text = text,
        style = TextStyle(
            color = colors.onSurface,
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

@Composable
internal fun DiagnosticBlock(text: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerHighest)
            .padding(16.dp),
    ) {
        SelectionContainer {
            BasicText(
                text = text,
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

@Composable
internal fun StatusPanel(
    state: PrivilegeSampleScreenState,
    onStopServer: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerLow)
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
                    color = colors.onSurfaceVariant,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                    textAlign = TextAlign.End,
                ),
            )
        }
        RuntimeInfoRow(label = "uid", value = state.serverInfo?.uid?.toString() ?: "-")
        RuntimeInfoRow(label = "pid", value = state.serverInfo?.pid?.toString() ?: "-")
        RuntimeInfoRow(label = "protocol", value = state.serverInfo?.protocolVersion?.toString() ?: "-")
        SampleAction(
            label = "Stop Server",
            enabled = !state.busy && state.status == PrivilegeSampleStatus.CONNECTED,
            tone = SampleActionTone.Destructive,
            onClick = onStopServer,
        )
    }
}

@Composable
internal fun SampleField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = label,
            style = TextStyle(
                color = colors.onSurfaceVariant,
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
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            cursorBrush = SolidColor(colors.primary),
        )
    }
}

internal enum class SampleActionTone {
    Primary,
    Secondary,
    Tonal,
    Destructive,
    Neutral,
}

@Composable
internal fun SampleAction(
    label: String,
    enabled: Boolean,
    tone: SampleActionTone,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val enabledColors = when (tone) {
        SampleActionTone.Primary -> colors.primary to colors.onPrimary
        SampleActionTone.Secondary -> colors.secondary to colors.onSecondary
        SampleActionTone.Tonal -> colors.secondaryContainer to colors.onSecondaryContainer
        SampleActionTone.Destructive -> colors.error to colors.onError
        SampleActionTone.Neutral -> colors.surfaceContainerHighest to colors.onSurface
    }
    val actualBackground = if (enabled) enabledColors.first else colors.onSurface.copy(alpha = 0.12f)
    val actualForeground = if (enabled) enabledColors.second else colors.onSurface.copy(alpha = 0.38f)
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
                color = actualForeground,
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
    val colors = MaterialTheme.colorScheme
    val text = when {
        busy -> "Busy"
        status == PrivilegeSampleStatus.CONNECTED -> "Connected"
        status == PrivilegeSampleStatus.STARTING -> "Starting"
        else -> "Disconnected"
    }
    val background = when {
        busy -> colors.primaryContainer
        status == PrivilegeSampleStatus.CONNECTED -> colors.tertiaryContainer
        else -> colors.surfaceContainerHigh
    }
    val foreground = when {
        busy -> colors.onPrimaryContainer
        status == PrivilegeSampleStatus.CONNECTED -> colors.onTertiaryContainer
        else -> colors.onSurfaceVariant
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
internal fun RuntimeInfoRow(
    label: String,
    value: String,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = colors.onSurfaceVariant,
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
            ),
        )
        Spacer(modifier = Modifier.width(16.dp))
        BasicText(
            text = value,
            style = TextStyle(
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
