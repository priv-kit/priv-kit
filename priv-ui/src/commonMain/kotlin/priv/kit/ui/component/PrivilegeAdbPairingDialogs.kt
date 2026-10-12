package priv.kit.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
    val defaultPairingMessage = stringResource(Res.string.priv_ui_pairing_default_message)
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
    AlertDialog(
        onDismissRequest = ::dismissOrStop,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(stringResource(Res.string.priv_ui_wireless_pair_dialog_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.medium)) {
                Column(verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.extraSmall)) {
                    Text(
                        text = state.pairingText

                            ?.takeIf(String::isNotBlank)
                            ?: defaultPairingMessage,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = pairingInputHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.pairingCode,
                    enabled = interactionEnabled,
                    onValueChange = actions.updatePairingCode,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSubmit) {
                                actions.submitNotificationPairingCode()
                            }
                        },
                    ),
                    label = { Text(stringResource(Res.string.priv_ui_pairing_code)) },
                    singleLine = true,
                )
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
