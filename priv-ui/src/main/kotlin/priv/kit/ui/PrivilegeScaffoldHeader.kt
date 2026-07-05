package priv.kit.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivilegeTopBar(
    onBackClick: () -> Unit,
    onHelpClick: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            val backDescription = stringResource(R.string.priv_ui_nav_back)
            PrivilegeIconTooltip(text = backDescription) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = PrivilegeUiIcons.ArrowBack,
                        contentDescription = backDescription,
                    )
                }
            }
        },
        title = {
            Text(
                text = stringResource(R.string.priv_ui_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        actions = {
            val helpDescription = stringResource(R.string.priv_ui_help)
            PrivilegeIconTooltip(text = helpDescription) {
                IconButton(onClick = onHelpClick) {
                    Icon(
                        imageVector = PrivilegeUiIcons.Help,
                        contentDescription = helpDescription,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
internal fun AuthorizationModeTabs(
    modes: List<PrivilegeUiStartupMode>,
    selectedMode: PrivilegeUiStartupMode,
    enabled: Boolean,
    onSelected: (PrivilegeUiStartupMode) -> Unit,
) {
    val selectedIndex = modes.indexOf(selectedMode).takeIf { it >= 0 } ?: 0
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        modes.forEach { mode ->
            Tab(
                selected = mode == selectedMode,
                enabled = enabled || mode == selectedMode,
                onClick = { onSelected(mode) },
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
