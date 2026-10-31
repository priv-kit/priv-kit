package priv.kit.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.Layout
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import priv.kit.ui.PrivilegeUiAdbPairingStatus
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import priv.kit.ui.adb.pairing.isPrivilegeUiPairingSessionActive
import priv.kit.ui.adb.pairing.isPrivilegeUiPairingCode

@Composable
internal fun PrivilegeUiScreenScope.WirelessAdbPairingNotificationPermissionWarningDialog() {
    AlertDialog(
        onDismissRequest = {
            if (interactionEnabled) actions.cancelPendingPairingStart()
        },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(stringResource(Res.string.priv_ui_notification_permission_unavailable_title))
        },
        text = {
            Text(stringResource(Res.string.priv_ui_notification_permission_unavailable_message))
        },
        confirmButton = {
            TextButton(
                enabled = interactionEnabled,
                onClick = actions.continuePairingWithoutNotification,
            ) {
                Text(stringResource(Res.string.priv_ui_pairing_continue_action))
            }
        },
        dismissButton = {
            TextButton(
                enabled = interactionEnabled,
                onClick = actions.cancelPendingPairingStart,
            ) {
                Text(stringResource(Res.string.priv_ui_pairing_cancel_action))
            }
            TextButton(
                enabled = interactionEnabled,
                onClick = {
                    actions.openNotificationSettings()
                },
            ) {
                Text(stringResource(Res.string.priv_ui_notification_permission_settings_action))
            }
        },
    )
}

@Composable
internal fun PrivilegeUiScreenScope.WirelessAdbPairingDialog() {
    var inputMethodsExpanded by rememberSaveable { mutableStateOf(false) }
    val inputMethodsState = stringResource(
        if (inputMethodsExpanded) Res.string.priv_ui_expanded else Res.string.priv_ui_collapsed,
    )
    val defaultPairingMessage = stringResource(Res.string.priv_ui_pairing_dialog_searching)
    val statusText = when (state.pairingStatus) {
        PrivilegeUiAdbPairingStatus.SEARCHING -> defaultPairingMessage
        PrivilegeUiAdbPairingStatus.FOUND -> stringResource(Res.string.priv_ui_pairing_dialog_found)
        else -> state.pairingText?.takeIf(String::isNotBlank) ?: defaultPairingMessage
    }
    val pairingInputHint = stringResource(
        privilegeUiPairingInputHint(state.notificationPairingRunning),
    )
    val pairing = state.pairingStatus == PrivilegeUiAdbPairingStatus.PAIRING
    val canSubmit = privilegeUiPairingCodeSubmitEnabled(
        pairingStatus = state.pairingStatus,
        pairingCode = state.pairingCode,
    ) && interactionEnabled
    fun dismissOrStop() {
        if (interactionEnabled) actions.stopNotificationPairing()
    }
    PairingDialog(
        onDismissRequest = ::dismissOrStop,
        title = stringResource(Res.string.priv_ui_wireless_pair_dialog_title),
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.small),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    value = state.pairingCode,
                    onValueChange = actions.updatePairingCode,
                    enabled = interactionEnabled,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSubmit) actions.submitNotificationPairingCode()
                        },
                    ),
                    label = { Text(stringResource(Res.string.priv_ui_pairing_code)) },
                    singleLine = true,
                )
                if (state.notificationPairingRunning) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .semantics { stateDescription = inputMethodsState }
                                .clickable(role = Role.Button) { inputMethodsExpanded = !inputMethodsExpanded }
                                .heightIn(min = 40.dp)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.priv_ui_pairing_input_methods),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = if (inputMethodsExpanded) PrivilegeUiIcons.KeyboardArrowUp
                                    else PrivilegeUiIcons.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        AnimatedVisibility(
                            visible = inputMethodsExpanded,
                            enter = expandVertically(expandFrom = Alignment.Top),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top),
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = pairingInputHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    PairingInputMethod(Res.string.priv_ui_pairing_method_typing, Res.string.priv_ui_pairing_method_typing_hint)
                                    PairingInputMethod(Res.string.priv_ui_pairing_method_buttons, Res.string.priv_ui_pairing_method_buttons_hint)
                                    PairingInputMethod(Res.string.priv_ui_pairing_method_split, Res.string.priv_ui_pairing_method_split_hint)
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        text = pairingInputHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            }
        },
        confirmButton = {
            PairingDialogActions {
                TutorialButton(
                    topic = priv.kit.ui.PrivilegeUiTutorialTopic.PAIRING,
                    label = Res.string.priv_ui_pairing_tutorial,
                )
                TextButton(
                    enabled = interactionEnabled,
                    onClick = ::dismissOrStop,
                ) {
                    Text(
                        stringResource(
                            if (pairing) Res.string.priv_ui_pairing_stop_action
                            else Res.string.priv_ui_pairing_cancel_action,
                        ),
                    )
                }
                TextButton(
                    enabled = canSubmit,
                    onClick = actions.submitNotificationPairingCode,
                ) {
                    Text(stringResource(Res.string.priv_ui_pairing_submit_action))
                }
            }
        },
    )
}

@Composable
private fun PairingDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .semantics { paneTitle = title },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    text()
                }
                Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    confirmButton()
                }
            }
        }
    }
}

@Composable
private fun PairingInputMethod(title: StringResource, description: StringResource) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Keeps help at the start and actions at the end, wrapping help above when necessary. */
@Composable
private fun PairingDialogActions(content: @Composable () -> Unit) {
    val spacing = PrivilegeUiSpacing.small
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val (help, cancel, submit) = measurables.map {
            it.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val actionsWidth = cancel.width + gap + submit.width
        val separateHelp = help.width + gap + actionsWidth > width
        val separateActions = actionsWidth > width
        val actionHeight = if (separateActions) cancel.height + gap + submit.height
            else maxOf(cancel.height, submit.height)
        val height = if (separateHelp) help.height + gap + actionHeight
            else maxOf(help.height, actionHeight)
        layout(width, height) {
            help.placeRelative(0, if (separateHelp) 0 else (height - help.height) / 2)
            val actionTop = if (separateHelp) help.height + gap else 0
            if (separateActions) {
                cancel.placeRelative(width - cancel.width, actionTop)
                submit.placeRelative(width - submit.width, actionTop + cancel.height + gap)
            } else {
                val rowHeight = if (separateHelp) actionHeight else height
                cancel.placeRelative(width - actionsWidth, actionTop + (rowHeight - cancel.height) / 2)
                submit.placeRelative(width - submit.width, actionTop + (rowHeight - submit.height) / 2)
            }
        }
    }
}

internal fun privilegeUiPairingCodeSubmitEnabled(
    pairingStatus: PrivilegeUiAdbPairingStatus,
    pairingCode: String,
): Boolean =
    pairingStatus != PrivilegeUiAdbPairingStatus.PAIRING &&
        pairingStatus.isPrivilegeUiPairingSessionActive() &&
        pairingCode.isPrivilegeUiPairingCode()

internal fun privilegeUiPairingInputHint(notificationPairingRunning: Boolean): StringResource =
    if (notificationPairingRunning) {
        Res.string.priv_ui_pairing_input_hint
    } else {
        Res.string.priv_ui_pairing_split_screen_hint
    }
