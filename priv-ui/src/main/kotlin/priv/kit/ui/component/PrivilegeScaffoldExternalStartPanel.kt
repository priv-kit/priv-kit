package priv.kit.ui.component

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
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.R

@Composable
internal fun PrivilegeUiScreenScope.ExternalStartPanel() {
    Panel {
        if (state.externalStartItems.isEmpty()) {
            Text(stringResource(R.string.priv_ui_external_no_provider))
        }
        state.externalStartItems.forEach { item ->
            ItemPanel {
                val action = state.startActionFor(
                    source = PrivilegeUiRuntimeStartSource.EXTERNAL,
                    providerId = item.id,
                )
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
                    StatusText(item.snapshot.externalStartStatusText())
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.startActionEnabled(action),
                    onClick = {
                        when (action) {
                            PrivilegeUiStartAction.START -> viewModel.authorizeOrStartExternal(item.id)
                            PrivilegeUiStartAction.CANCEL -> viewModel.stopCurrentStart()
                            PrivilegeUiStartAction.CANCELLING,
                            PrivilegeUiStartAction.NONE,
                            -> Unit
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            privilegeUiStartActionLabel(
                                action = action,
                                startLabel = if (item.snapshot.canStart) {
                                    R.string.priv_ui_external_start
                                } else {
                                    R.string.priv_ui_external_authorize_start
                                },
                            ),
                        ),
                    )
                }
            }
        }
    }
}
