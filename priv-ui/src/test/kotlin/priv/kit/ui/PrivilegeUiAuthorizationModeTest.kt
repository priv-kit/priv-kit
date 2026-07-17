package priv.kit.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.ui.component.PrivilegeUiAuthorizationModeSelector
import priv.kit.ui.component.privilegeUiAuthorizationModeItems
import priv.kit.ui.component.privilegeUiAuthorizationModeSelector

class PrivilegeUiAuthorizationModeTest {
    private val modes = listOf(
        PrivilegeUiStartupMode.ROOT,
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
    )

    @Test
    fun selectedModeResolvesToItsItem() {
        val items = privilegeUiAuthorizationModeItems(
            modes = modes,
            selectedMode = PrivilegeUiStartupMode.ADB,
            busy = false,
        )

        assertEquals(1, items.indexOfFirst { it.selected })
        assertTrue(items.all { it.enabled })
    }

    @Test
    fun unavailableSelectionFallsBackToFirstMode() {
        val items = privilegeUiAuthorizationModeItems(
            modes = modes,
            selectedMode = PrivilegeUiStartupMode.EXTERNAL,
            busy = false,
        )

        assertEquals(0, items.indexOfFirst { it.selected })
        assertEquals(PrivilegeUiStartupMode.ROOT, items.first().mode)
    }

    @Test
    fun busyStateOnlyKeepsResolvedSelectionEnabled() {
        val items = privilegeUiAuthorizationModeItems(
            modes = modes,
            selectedMode = PrivilegeUiStartupMode.ADB,
            busy = true,
        )

        assertTrue(items.single { it.mode == PrivilegeUiStartupMode.ADB }.enabled)
        assertFalse(items.single { it.mode == PrivilegeUiStartupMode.ROOT }.enabled)
        assertFalse(items.single { it.mode == PrivilegeUiStartupMode.MANUAL_SHELL }.enabled)
    }

    @Test
    fun selectorUsesSegmentedControlOnlyWhenContentIsWideEnough() {
        assertEquals(
            PrivilegeUiAuthorizationModeSelector.NONE,
            privilegeUiAuthorizationModeSelector(modeCount = 0, availableWidth = 420.dp),
        )
        assertEquals(
            PrivilegeUiAuthorizationModeSelector.NONE,
            privilegeUiAuthorizationModeSelector(modeCount = 1, availableWidth = 420.dp),
        )
        assertEquals(
            PrivilegeUiAuthorizationModeSelector.SCROLLABLE_TABS,
            privilegeUiAuthorizationModeSelector(modeCount = modes.size, availableWidth = 419.dp),
        )
        assertEquals(
            PrivilegeUiAuthorizationModeSelector.SEGMENTED,
            privilegeUiAuthorizationModeSelector(modeCount = modes.size, availableWidth = 420.dp),
        )
    }
}
