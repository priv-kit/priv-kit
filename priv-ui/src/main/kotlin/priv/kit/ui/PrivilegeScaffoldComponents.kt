package priv.kit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun ItemPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
internal fun ServiceStatusPanel(state: PrivilegeUiState) {
    val running = state.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED
    val (title, background, foreground) = if (running) {
        StatusUi(
            title = stringResource(R.string.priv_ui_service_started),
            background = Color(0xFFDFF5E7),
            foreground = Color(0xFF176B36),
        )
    } else {
        StatusUi(
            title = stringResource(R.string.priv_ui_service_not_started),
            background = MaterialTheme.colorScheme.surface,
            foreground = MaterialTheme.colorScheme.onSurface,
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun PrivilegeUiDelegateSnapshot.delegateStatusText(): String =
    when {
        canStart -> stringResource(R.string.priv_ui_delegate_ready)
        available -> stringResource(R.string.priv_ui_delegate_permission_required)
        else -> stringResource(R.string.priv_ui_delegate_unavailable)
    }

private data class StatusUi(
    val title: String,
    val background: Color,
    val foreground: Color,
)
