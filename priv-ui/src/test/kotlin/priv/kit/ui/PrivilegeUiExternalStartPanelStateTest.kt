package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegeUiExternalStartPanelStateTest {
    @Test
    fun readyExternalProviderMessageIsHidden() {
        assertNull(
            PrivilegeUiExternalStartSnapshot(
                available = true,
                authorized = true,
                message = "Shizuku is authorized",
            ).externalStartMessageText(),
        )
    }

    @Test
    fun unavailableExternalProviderMessageIsShown() {
        assertEquals(
            "Shizuku is not running",
            PrivilegeUiExternalStartSnapshot(
                message = "Shizuku is not running",
            ).externalStartMessageText(),
        )
    }
}
