package priv.kit.ui.component

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.PrivilegeUiTutorialTopic
import priv.kit.ui.openPrivilegeUiTutorial
import priv.kit.ui.resources.*

@Composable
internal fun PrivilegeUiScreenScope.tutorialAction(topic: PrivilegeUiTutorialTopic): () -> Unit {
    val uriHandler = LocalUriHandler.current
    val defaultUrl = stringResource(Res.string.priv_ui_startup_tutorial_url)
    val failure = stringResource(Res.string.priv_ui_tutorial_open_failed)
    return {
        openPrivilegeUiTutorial(
            topic = topic,
            baseUrl = startupTutorialUrl ?: defaultUrl,
            onViewPermissionSolutions = onViewPermissionSolutions,
            onViewStartupTutorial = onViewStartupTutorial,
            openUri = uriHandler::openUri,
            onOpenFailure = { showFeedback(failure) },
        )
    }
}

@Composable
internal fun PrivilegeUiScreenScope.TutorialButton(
    topic: PrivilegeUiTutorialTopic,
    label: StringResource = Res.string.priv_ui_startup_tutorial,
    outlined: Boolean = false,
) {
    val content: @Composable RowScope.() -> Unit = {
        Text(stringResource(label))
        if (topic == PrivilegeUiTutorialTopic.ADB || topic == PrivilegeUiTutorialTopic.MANUAL) {
            Spacer(Modifier.width(PrivilegeUiSpacing.small))
            Icon(
                imageVector = PrivilegeUiIcons.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    if (outlined) {
        OutlinedButton(
            onClick = tutorialAction(topic),
            shape = ButtonDefaults.shape,
            content = content,
        )
    } else {
        TextButton(onClick = tutorialAction(topic), content = content)
    }
}
