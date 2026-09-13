package priv.kit.playground

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import priv.kit.ui.PrivilegePreviewScaffold
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.compose.resources.Font
import priv.kit.playground.resources.Res
import priv.kit.playground.resources.noto_sans_sc

@Composable
internal fun PrivilegePlaygroundApp(
    dark: Boolean = isSystemInDarkTheme(),
    useLegacyPackaging: Boolean = true,
) {
    val fontFamily = FontFamily(Font(Res.font.noto_sans_sc))
    val defaults = Typography()
    val typography = defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = defaults.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = defaults.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = defaults.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = defaults.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = defaults.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = defaults.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = defaults.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = defaults.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = defaults.labelSmall.copy(fontFamily = fontFamily),
    )
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        typography = typography,
    ) {
        PrivilegePreviewScaffold(useLegacyPackaging = useLegacyPackaging)
    }
}
