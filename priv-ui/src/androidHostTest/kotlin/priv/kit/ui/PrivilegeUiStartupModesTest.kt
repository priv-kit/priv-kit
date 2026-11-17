package priv.kit.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.core.PrivilegeExternalStartProvider
import priv.kit.core.PrivilegeExternalStartResult
import priv.kit.core.PrivilegeStartupLogListener

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

    private object TestExternalStartProvider : PrivilegeExternalStartProvider {
        override val id: String = "test"
        override fun getLabel(context: Context): CharSequence = "Test"

        override suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult =
            PrivilegeExternalStartResult(authorized = null)

        override suspend fun requestAuthorization(context: Context): PrivilegeExternalStartResult =
            error("Unexpected authorization request")

        override suspend fun runStarter(
            context: Context,
            commandLine: String,
            startupLogListener: PrivilegeStartupLogListener?,
        ): String = error("Unexpected start")
    }
}
