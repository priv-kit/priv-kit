package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUiWirelessAdbStartButtonTest {
    @Test
    fun startingRuntimeKeepsWirelessStartButtonClickableAsStop() {
        val action = privilegeUiWirelessAdbStartAction(
            runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
            wifiConnected = true,
            startPrerequisiteAvailable = true,
            startAvailable = true,
        )

        assertEquals(PrivilegeUiWirelessAdbStartAction.STOP, action)
        assertTrue(
            privilegeUiWirelessAdbStartActionEnabled(
                action = action,
                busy = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
            ),
        )
        assertEquals(
            R.string.priv_ui_adb_wireless_stop_action,
            privilegeUiWirelessAdbStartActionLabel(action),
        )
    }

    @Test
    fun unrelatedBusyStateDoesNotTurnWirelessStartIntoStop() {
        val action = privilegeUiWirelessAdbStartAction(
            runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
            wifiConnected = true,
            startPrerequisiteAvailable = true,
            startAvailable = true,
        )

        assertEquals(PrivilegeUiWirelessAdbStartAction.START, action)
        assertFalse(
            privilegeUiWirelessAdbStartActionEnabled(
                action = action,
                busy = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
            ),
        )
        assertEquals(
            R.string.priv_ui_adb_wireless_start_action,
            privilegeUiWirelessAdbStartActionLabel(action),
        )
    }

    @Test
    fun wirelessStartButtonPromptsWhenWifiIsUnavailable() {
        val action = privilegeUiWirelessAdbStartAction(
            runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
            wifiConnected = false,
            startPrerequisiteAvailable = true,
            startAvailable = false,
        )

        assertEquals(PrivilegeUiWirelessAdbStartAction.WIFI_REQUIRED, action)
        assertTrue(
            privilegeUiWirelessAdbStartActionEnabled(
                action = action,
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
            ),
        )
        assertEquals(
            R.string.priv_ui_adb_wireless_wifi_required_action,
            privilegeUiWirelessAdbStartActionLabel(action),
        )
    }

    @Test
    fun wirelessStartButtonHasNoActionWithoutStartPrerequisite() {
        val action = privilegeUiWirelessAdbStartAction(
            runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
            wifiConnected = false,
            startPrerequisiteAvailable = false,
            startAvailable = false,
        )

        assertEquals(PrivilegeUiWirelessAdbStartAction.NONE, action)
        assertFalse(
            privilegeUiWirelessAdbStartActionEnabled(
                action = action,
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
            ),
        )
    }
}
