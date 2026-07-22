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
        val text = privilegeUiText(R.string.priv_ui_service_source, "Shell")

        assertEquals("Source: Shell", text.asString(localizedContext("en")))
        assertEquals("来源：Shell", text.asString(localizedContext("zh-CN")))
    }

    @Test
    fun literalTextDoesNotChangeWithThePresentationLocale() {
        val text = PrivilegeUiText.Literal("external provider message")

        assertEquals("external provider message", text.asString(localizedContext("en")))
        assertEquals("external provider message", text.asString(localizedContext("zh-CN")))
    }

    private fun localizedContext(languageTag: String): Context {
        val base: Context = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        return base.createConfigurationContext(configuration)
    }
}
