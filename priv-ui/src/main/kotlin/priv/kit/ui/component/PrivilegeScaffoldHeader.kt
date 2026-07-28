package priv.kit.ui.component

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.PrivilegeUiStartupMode
import priv.kit.ui.PrivilegeUiViewModel
import priv.kit.ui.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivilegeTopBar(viewModel: PrivilegeUiViewModel) {
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val uriHandler = LocalUriHandler.current
    TopAppBar(
        navigationIcon = {
            val backDescription = stringResource(R.string.priv_ui_nav_back)
            PrivilegeIconTooltip(
                text = backDescription,
                modifier = Modifier,
            ) {
                IconButton(
                    onClick = {
                        if (!viewModel.dispatchBackClick()) {
                            backDispatcher?.onBackPressed()
                        }
                    },
                ) {
                    Icon(
                        imageVector = PrivilegeUiIcons.ArrowBack,
                        contentDescription = backDescription,
                    )
                }
            }
        },
        title = {
            Text(text = stringResource(R.string.priv_ui_title))
        },
        actions = {
            val githubDescription = stringResource(R.string.priv_ui_github_repository_action)
            PrivilegeIconTooltip(
                text = githubDescription,
                modifier = Modifier,
            ) {
                IconButton(
                    onClick = uriHandler::openPrivilegeUiGitHubRepository,
                ) {
                    Icon(
                        imageVector = PrivilegeUiIcons.GitHub,
                        contentDescription = githubDescription,
                    )
                }
            }
        },
    )
}

internal fun UriHandler.openPrivilegeUiGitHubRepository() {
    runCatching {
        openUri(PRIVILEGE_UI_GITHUB_URL)
    }
}

private const val PRIVILEGE_UI_GITHUB_URL = "https://github.com/priv-kit/priv-kit"

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
                onClick = { viewModel.selectStartupMode(item.mode) },
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

internal fun PrivilegeUiStartupMode.labelRes(): Int =
    when (this) {
        PrivilegeUiStartupMode.ROOT -> R.string.priv_ui_auth_method_root
        PrivilegeUiStartupMode.MANUAL_SHELL -> R.string.priv_ui_auth_method_manual_shell
        PrivilegeUiStartupMode.ADB -> R.string.priv_ui_auth_method_adb
        PrivilegeUiStartupMode.EXTERNAL -> R.string.priv_ui_auth_method_external
    }
