package priv.kit.ui.component

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.shared.PRIVILEGE_INTERNAL_ROOT_UID
import priv.kit.shared.PRIVILEGE_INTERNAL_SHELL_UID
import priv.kit.shared.PRIVILEGE_INTERNAL_SYSTEM_UID
import priv.kit.ui.R
import priv.kit.ui.asString
import priv.kit.ui.testServerInfo
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiRuntimeSourceTextTest {
    @Test
    fun knownProcessUidsResolveToNamedSources() {
        assertSource(uid = PRIVILEGE_INTERNAL_ROOT_UID, english = "Source: Root", chinese = "来源：Root")
        assertSource(
            uid = PRIVILEGE_INTERNAL_SYSTEM_UID,
            english = "Source: System",
            chinese = "来源：System",
        )
        assertSource(uid = PRIVILEGE_INTERNAL_SHELL_UID, english = "Source: Shell", chinese = "来源：Shell")
    }

    @Test
    fun otherProcessUidIncludesItsNumericValue() {
        assertSource(uid = 12_345, english = "Source: uid(12345)", chinese = "来源：uid(12345)")
    }

    @Test
    fun missingServerInfoRemainsUnknown() {
        assertSource(uid = null, english = "Source: Unknown", chinese = "来源：未知")
    }

    private fun assertSource(uid: Int?, english: String, chinese: String) {
        assertEquals(english, sourceText(uid, "en"))
        assertEquals(chinese, sourceText(uid, "zh-CN"))
    }

    private fun sourceText(uid: Int?, languageTag: String): String {
        val context = localizedContext(languageTag)
        val serverInfo = uid?.let {
            testServerInfo(uid = it, pid = 123, protocolVersion = 1)
        }
        val source = serverInfo.runtimeSourceText().asString(context)
        return context.getString(R.string.priv_ui_service_source, source)
    }

    private fun localizedContext(languageTag: String): Context {
        val base: Context = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        return base.createConfigurationContext(configuration)
    }
}
