package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import priv.kit.core.PrivilegeStartupLogLine

class PrivilegeUiViewModelStoreTest {
    @Test
    fun appendStartupLogFormatsSourceAndSplitsLines() {
        val store = PrivilegeUiViewModelStore()
        try {
            store.appendStartupLog(
                PrivilegeStartupLogLine(
                    source = "adb",
                    message = "first\nsecond",
                ),
            )

            assertEquals(
                listOf("[adb] first", "[adb] second"),
                store.state.value.startupLogLines,
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun appendStartupLogKeepsRecentLinesOnlyAndCanClear() {
        val store = PrivilegeUiViewModelStore()
        try {
            repeat(250) { index ->
                store.appendStartupLog("line-$index")
            }

            assertEquals(240, store.state.value.startupLogLines.size)
            assertEquals("line-10", store.state.value.startupLogLines.first())
            assertEquals("line-249", store.state.value.startupLogLines.last())

            store.clearStartupLog()

            assertEquals(emptyList<String>(), store.state.value.startupLogLines)
        } finally {
            store.close()
        }
    }
}
