package priv.kit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun DelegatePanel(
    state: PrivilegeUiState,
    onAuthorizeOrStart: (String) -> Unit,
) {
    Panel {
        Text(
            text = stringResource(R.string.priv_ui_delegate_authorization_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.delegateItems.isEmpty()) {
            Text(stringResource(R.string.priv_ui_delegate_no_provider))
        }
        state.delegateItems.forEach { item ->
            ItemPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusText(item.snapshot.delegateStatusText())
                }
                if (item.snapshot.message.isNotBlank()) {
                    Text(
                        text = item.snapshot.message.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy && !item.busy,
                    onClick = { onAuthorizeOrStart(item.id) },
                ) {
                    Text(
                        if (item.snapshot.canStart) {
                            stringResource(R.string.priv_ui_delegate_start)
                        } else {
                            stringResource(R.string.priv_ui_delegate_authorize_start)
                        },
                    )
                }
            }
        }
    }
}
