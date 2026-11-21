package priv.kit.ui

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiTextTest {
    @Test
    fun resourceTextResolvesAgainstTheContextAtUseTime() {
        val text = privilegeUiText(R.string.priv_ui_startup_source, "Shell")

        assertEquals("Startup source: Shell", text.asString(localizedContext("en")))
        assertEquals("启动来源：Shell", text.asString(localizedContext("zh-CN")))
    }

    @Test
    fun literalTextDoesNotChangeWithThePresentationLocale() {
        val text = PrivilegeUiText.Literal("external provider message")

        assertEquals("external provider message", text.asString(localizedContext("en")))
        assertEquals("external provider message", text.asString(localizedContext("zh-CN")))
    }

    @Test
    fun sharedScreenMappingResolvesRetainedTextForEachLocale() {
        val state = PrivilegeUiState(
            busy = true,
            runtimeProgressText = privilegeUiText(R.string.priv_ui_startup_source, "Shell"),
            pairingText = PrivilegeUiText.Literal("pairing details"),
        )
        val english = state.toScreenState { it.asString(localizedContext("en")) }
        val chinese = state.toScreenState { it.asString(localizedContext("zh-CN")) }
        assertEquals("Startup source: Shell", english.runtimeProgressText)
        assertEquals("启动来源：Shell", chinese.runtimeProgressText)
        assertEquals("pairing details", chinese.pairingText)
        assertEquals(true, chinese.busy)
    }

    private fun localizedContext(languageTag: String): Context {
        val base: Context = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        return base.createConfigurationContext(configuration)
    }
}
