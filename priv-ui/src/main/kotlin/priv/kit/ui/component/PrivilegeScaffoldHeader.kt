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
    TopAppBar(
        navigationIcon = {
            val backDescription = stringResource(R.string.priv_ui_nav_back)
            PrivilegeIconTooltip(text = backDescription) {
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
    )
}

@Composable
internal fun PrivilegeUiScreenScope.AuthorizationModeTabs() {
    val modes = state.startupModes
    val selectedMode = state.selectedStartupMode
    val selectedIndex = modes.indexOf(selectedMode).takeIf { it >= 0 } ?: 0
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 0.dp,
    ) {
        modes.forEach { mode ->
            Tab(
                selected = mode == selectedMode,
                enabled = !state.busy || mode == selectedMode,
                onClick = { viewModel.selectStartupMode(mode) },
                text = {
                    Text(
                        text = stringResource(mode.labelRes()),
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

internal fun PrivilegeUiStartupMode.labelRes(): Int =
    when (this) {
        PrivilegeUiStartupMode.ROOT -> R.string.priv_ui_auth_method_root
        PrivilegeUiStartupMode.MANUAL_SHELL -> R.string.priv_ui_auth_method_manual_shell
        PrivilegeUiStartupMode.ADB -> R.string.priv_ui_auth_method_adb
        PrivilegeUiStartupMode.EXTERNAL -> R.string.priv_ui_auth_method_external
    }
