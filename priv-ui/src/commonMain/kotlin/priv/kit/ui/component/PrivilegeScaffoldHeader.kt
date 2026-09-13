package priv.kit.ui.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.PrivilegeUiStartupMode
import priv.kit.ui.resources.*
import org.jetbrains.compose.resources.StringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivilegeTopBar(onBack: () -> Unit, backEnabled: Boolean = true) {
    TopAppBar(
        navigationIcon = {
            val backDescription = stringResource(Res.string.priv_ui_nav_back)
            PrivilegeIconTooltip(
                text = backDescription,
                modifier = Modifier,
            ) {
                IconButton(
                    enabled = backEnabled,
                    onClick = onBack,
                ) {
                    Icon(
                        imageVector = PrivilegeUiIcons.ArrowBack,
                        contentDescription = backDescription,
                    )
                }
            }
        },
        title = {
            Text(text = stringResource(Res.string.priv_ui_title))
        },
    )
}

@Composable
internal fun PrivilegeUiScreenScope.AuthorizationModeTabs() {
    val items = privilegeUiAuthorizationModeItems(
        modes = state.startupModes,
        selectedMode = state.selectedStartupMode,
        busy = state.busy,
        interactionEnabled = interactionEnabled,
    )
    PrimaryScrollableTabRow(
        selectedTabIndex = items.indexOfFirst { it.selected },
        edgePadding = 0.dp,
    ) {
        items.forEach { item ->
            Tab(
                selected = item.selected,
                enabled = item.enabled,
                onClick = { actions.selectStartupMode(item.mode) },
                text = {
                    Text(
                        text = stringResource(item.mode.labelRes()),
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

internal data class PrivilegeUiAuthorizationModeItem(
    val mode: PrivilegeUiStartupMode,
    val selected: Boolean,
    val enabled: Boolean,
)

internal fun privilegeUiAuthorizationModeItems(
    modes: List<PrivilegeUiStartupMode>,
    selectedMode: PrivilegeUiStartupMode,
    busy: Boolean,
    interactionEnabled: Boolean,
): List<PrivilegeUiAuthorizationModeItem> {
    val resolvedMode = selectedMode.takeIf { it in modes } ?: modes.firstOrNull()
    return modes.map { mode ->
        PrivilegeUiAuthorizationModeItem(
            mode = mode,
            selected = mode == resolvedMode,
            enabled = interactionEnabled && (!busy || mode == resolvedMode),
        )
    }
}

internal fun PrivilegeUiStartupMode.labelRes(): StringResource =
    when (this) {
        PrivilegeUiStartupMode.ROOT -> Res.string.priv_ui_auth_method_root
        PrivilegeUiStartupMode.MANUAL_SHELL -> Res.string.priv_ui_auth_method_manual_shell
        PrivilegeUiStartupMode.ADB -> Res.string.priv_ui_auth_method_adb
        PrivilegeUiStartupMode.EXTERNAL -> Res.string.priv_ui_auth_method_external
    }
