package priv.kit.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivilegeUiStartupModesTest {
    @Test
    fun configuredOrderControlsStartupModeOrder() {
        val config = PrivilegeUiConfig(
            startupModes = listOf(
                PrivilegeUiStartupMode.MANUAL_SHELL,
                PrivilegeUiStartupMode.ROOT,
                PrivilegeUiStartupMode.ADB,
            ),
        )

        assertEquals(config.startupModes, config.effectiveStartupModes())
    }

    @Test
    fun externalModeUsesConfiguredPositionWhenProvidersExist() {
        val config = PrivilegeUiConfig(
            startupModes = listOf(
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.EXTERNAL,
                PrivilegeUiStartupMode.ROOT,
            ),
            externalStartProviders = listOf(TestExternalStartProvider),
        )

        assertEquals(config.startupModes, config.effectiveStartupModes())
    }

    @Test
    fun externalModeIsAppendedWhenProvidersExistAndModeIsOmitted() {
        val config = PrivilegeUiConfig(
            startupModes = listOf(
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.ROOT,
            ),
            externalStartProviders = listOf(TestExternalStartProvider),
        )

        assertEquals(
            listOf(
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.ROOT,
                PrivilegeUiStartupMode.EXTERNAL,
            ),
            config.effectiveStartupModes(),
        )
    }

    @Test
    fun externalModeIsHiddenWhenProvidersAreEmpty() {
        val config = PrivilegeUiConfig(
            startupModes = listOf(
                PrivilegeUiStartupMode.EXTERNAL,
                PrivilegeUiStartupMode.ADB,
            ),
        )

        assertEquals(
            listOf(PrivilegeUiStartupMode.ADB),
            config.effectiveStartupModes(),
        )
    }

    @Test
    fun duplicateStartupModesAreRejected() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            PrivilegeUiConfig(
                startupModes = listOf(
                    PrivilegeUiStartupMode.ADB,
                    PrivilegeUiStartupMode.ADB,
                ),
            )
        }

        assertEquals("startup modes must be unique", exception.message)
    }

    private object TestExternalStartProvider : PrivilegeUiExternalStartProvider {
        override val id: String = "test"
        override val label: CharSequence = "Test"

        override suspend fun start(
            context: Context,
            commandLine: String,
        ) = Unit
    }
}
