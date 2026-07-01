package priv.kit.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
internal data class PrivilegeSampleColors(
    val pageBackground: Color,
    val panelBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val tabBackground: Color,
    val tabSelectedBackground: Color,
    val tabDisabledBackground: Color,
    val actionPrimary: Color,
    val actionSuccess: Color,
    val actionDanger: Color,
    val actionWarning: Color,
    val actionAccent: Color,
    val actionNeutral: Color,
    val actionMuted: Color,
    val actionLog: Color,
    val actionDisabled: Color,
    val actionForeground: Color,
    val fieldBackground: Color,
    val fieldText: Color,
    val statusInfoBackground: Color,
    val statusInfoForeground: Color,
    val statusSuccessBackground: Color,
    val statusSuccessForeground: Color,
    val statusDangerBackground: Color,
    val statusDangerForeground: Color,
    val statusNeutralBackground: Color,
    val statusNeutralForeground: Color,
    val terminalBackground: Color,
    val terminalText: Color,
    val terminalMutedText: Color,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1769E0),
    onPrimary = Color.White,
    secondary = Color(0xFF5E6873),
    onSecondary = Color.White,
    tertiary = Color(0xFF087443),
    onTertiary = Color.White,
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF101418),
    surface = Color.White,
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE2E7EE),
    onSurfaceVariant = Color(0xFF48525C),
    error = Color(0xFFB42318),
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8DBBFF),
    onPrimary = Color(0xFF092847),
    secondary = Color(0xFFB8C4D0),
    onSecondary = Color(0xFF22303D),
    tertiary = Color(0xFF74D69A),
    onTertiary = Color(0xFF09391D),
    background = Color(0xFF101419),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF1B2027),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF2B333D),
    onSurfaceVariant = Color(0xFFC2CAD4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF5F1510),
)

private val LightSampleColors = PrivilegeSampleColors(
    pageBackground = Color(0xFFF6F7F9),
    panelBackground = Color.White,
    textPrimary = Color(0xFF101418),
    textSecondary = Color(0xFF27313B),
    textMuted = Color(0xFF48525C),
    textSubtle = Color(0xFF5E6873),
    tabBackground = Color(0xFFE2E7EE),
    tabSelectedBackground = Color(0xFF1769E0),
    tabDisabledBackground = Color(0xFFCAD3DD),
    actionPrimary = Color(0xFF1769E0),
    actionSuccess = Color(0xFF087443),
    actionDanger = Color(0xFFB42318),
    actionWarning = Color(0xFF7A4E1D),
    actionAccent = Color(0xFF5E4FA2),
    actionNeutral = Color(0xFF66717D),
    actionMuted = Color(0xFF5E6873),
    actionLog = Color(0xFF2A3541),
    actionDisabled = Color(0xFF9DA8B5),
    actionForeground = Color.White,
    fieldBackground = Color.White,
    fieldText = Color(0xFF101418),
    statusInfoBackground = Color(0xFFEAF1FF),
    statusInfoForeground = Color(0xFF1769E0),
    statusSuccessBackground = Color(0xFFE4F4EA),
    statusSuccessForeground = Color(0xFF16743A),
    statusDangerBackground = Color(0xFFFFECE8),
    statusDangerForeground = Color(0xFFB42318),
    statusNeutralBackground = Color(0xFFF1F3F5),
    statusNeutralForeground = Color(0xFF48525C),
    terminalBackground = Color(0xFF111820),
    terminalText = Color(0xFFF7FAFC),
    terminalMutedText = Color(0xFFB8C4D0),
)

private val DarkSampleColors = PrivilegeSampleColors(
    pageBackground = Color(0xFF101419),
    panelBackground = Color(0xFF1B2027),
    textPrimary = Color(0xFFE6EAF0),
    textSecondary = Color(0xFFD4DAE3),
    textMuted = Color(0xFFB6C0CB),
    textSubtle = Color(0xFF9AA5B2),
    tabBackground = Color(0xFF2B333D),
    tabSelectedBackground = Color(0xFF2F75D6),
    tabDisabledBackground = Color(0xFF242A32),
    actionPrimary = Color(0xFF2F75D6),
    actionSuccess = Color(0xFF138557),
    actionDanger = Color(0xFFD24D45),
    actionWarning = Color(0xFF8F6432),
    actionAccent = Color(0xFF7668C8),
    actionNeutral = Color(0xFF4E5966),
    actionMuted = Color(0xFF5C6875),
    actionLog = Color(0xFF2A3541),
    actionDisabled = Color(0xFF3D4652),
    actionForeground = Color.White,
    fieldBackground = Color(0xFF242A32),
    fieldText = Color(0xFFE6EAF0),
    statusInfoBackground = Color(0xFF14345C),
    statusInfoForeground = Color(0xFF9CC7FF),
    statusSuccessBackground = Color(0xFF123922),
    statusSuccessForeground = Color(0xFF84D99B),
    statusDangerBackground = Color(0xFF4A1B18),
    statusDangerForeground = Color(0xFFFFB4AB),
    statusNeutralBackground = Color(0xFF252B33),
    statusNeutralForeground = Color(0xFFC2CAD4),
    terminalBackground = Color(0xFF0E131A),
    terminalText = Color(0xFFF7FAFC),
    terminalMutedText = Color(0xFFB8C4D0),
)

private val LocalPrivilegeSampleColors = staticCompositionLocalOf { LightSampleColors }

internal val sampleColors: PrivilegeSampleColors
    @Composable
    @ReadOnlyComposable
    get() = LocalPrivilegeSampleColors.current

@Composable
internal fun PrivilegeSampleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkSampleColors else LightSampleColors
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
    ) {
        CompositionLocalProvider(
            LocalPrivilegeSampleColors provides colors,
            content = content,
        )
    }
}
