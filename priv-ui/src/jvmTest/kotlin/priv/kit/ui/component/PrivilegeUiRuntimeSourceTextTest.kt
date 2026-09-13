package priv.kit.ui.component

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import priv.kit.ui.resources.Res
import priv.kit.ui.resources.priv_ui_service_source
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivilegeUiRuntimeSourceTextTest {
    @Test
    fun knownProcessUidsResolveToNamedSources() = runTest {
        assertSource(uid = 0, english = "Source: Root", chinese = "来源：Root")
        assertSource(uid = 1000, english = "Source: System", chinese = "来源：System")
        assertSource(uid = 2000, english = "Source: Shell", chinese = "来源：Shell")
    }

    @Test
    fun otherProcessUidIncludesItsNumericValue() = runTest {
        assertSource(uid = 12_345, english = "Source: uid(12345)", chinese = "来源：uid(12345)")
    }

    @Test
    fun missingServerInfoRemainsUnknown() = runTest {
        assertSource(uid = null, english = "Source: Unknown", chinese = "来源：未知")
    }

    private suspend fun assertSource(uid: Int?, english: String, chinese: String) {
        val previousLocale = Locale.getDefault()
        try {
            for ((locale, expected) in listOf(Locale.ENGLISH to english, Locale.SIMPLIFIED_CHINESE to chinese)) {
                Locale.setDefault(locale)
                val source = getString(runtimeSourceLabel(uid), uid ?: 0)
                assertEquals(expected, getString(Res.string.priv_ui_service_source, source))
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
