package priv.kit.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.core.PrivilegeLaunchMode
import priv.kit.core.PrivilegeProtocol

class PrivilegeServerArgumentsTest {
    @Test
    fun parseAcceptsRequiredRuntimeConfig() {
        val config = PrivilegeServerArguments.parse(requiredArgs())

        assertEquals("", config.token)
        assertEquals("classpath@1@2", config.classpathIdentity)
        assertEquals(10, config.userId)
        assertEquals(PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS, config.followDeathDelayMillis)
        assertFalse(config.activeReconnectOnOwnerDeath)
    }

    @Test
    fun parseAcceptsExplicitToken() {
        val config = PrivilegeServerArguments.parse(
            requiredArgs(
                "--token",
                "token",
            ),
        )

        assertEquals("token", config.token)
    }

    @Test
    fun parseAcceptsFollowDeathDelay() {
        val config = PrivilegeServerArguments.parse(
            requiredArgs(
                "--follow-death-delay-millis",
                "1234",
            ),
        )

        assertEquals(1234L, config.followDeathDelayMillis)
    }

    @Test
    fun parseAcceptsActiveReconnectOnOwnerDeath() {
        val config = PrivilegeServerArguments.parse(
            requiredArgs(
                "--active-reconnect-on-owner-death",
                "true",
            ),
        )

        assertTrue(config.activeReconnectOnOwnerDeath)
    }

    @Test
    fun parseDefaultsMissingUserIdToSystemUser() {
        val config = PrivilegeServerArguments.parse(requiredArgsWithoutUserId())

        assertEquals(0, config.userId)
    }

    @Test
    fun parseRejectsNegativeFollowDeathDelay() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeServerArguments.parse(
                requiredArgs(
                    "--follow-death-delay-millis",
                    "-1",
                ),
            )
        }
    }

    @Test
    fun parseRejectsInvalidActiveReconnectOnOwnerDeath() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeServerArguments.parse(
                requiredArgs(
                    "--active-reconnect-on-owner-death",
                    "yes",
                ),
            )
        }
    }

    @Test
    fun parseRejectsMissingRuntimeConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeServerArguments.parse(requiredArgsWithoutRuntimeConfig())
        }
    }

    private fun requiredArgs(vararg extraArgs: String): Array<String> =
        arrayOf(
            "--provider-authority",
            "example.privilege.handshake",
            "--package-name",
            "example",
            "--user-id",
            "10",
            "--launch-mode",
            PrivilegeLaunchMode.SHELL.value.toString(),
            "--protocol-version",
            PrivilegeProtocol.VERSION.toString(),
            "--server-version",
            PrivilegeProtocol.SERVER_VERSION,
            "--classpath-identity",
            "classpath@1@2",
            "--follow-death-delay-millis",
            PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS.toString(),
            "--active-reconnect-on-owner-death",
            PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH.toString(),
            *extraArgs,
        )

    private fun requiredArgsWithoutRuntimeConfig(): Array<String> =
        arrayOf(
            "--provider-authority",
            "example.privilege.handshake",
            "--package-name",
            "example",
            "--user-id",
            "10",
            "--launch-mode",
            PrivilegeLaunchMode.SHELL.value.toString(),
            "--protocol-version",
            PrivilegeProtocol.VERSION.toString(),
            "--server-version",
            PrivilegeProtocol.SERVER_VERSION,
            "--classpath-identity",
            "classpath@1@2",
        )

    private fun requiredArgsWithoutUserId(): Array<String> =
        arrayOf(
            "--provider-authority",
            "example.privilege.handshake",
            "--package-name",
            "example",
            "--launch-mode",
            PrivilegeLaunchMode.SHELL.value.toString(),
            "--protocol-version",
            PrivilegeProtocol.VERSION.toString(),
            "--server-version",
            PrivilegeProtocol.SERVER_VERSION,
            "--classpath-identity",
            "classpath@1@2",
            "--follow-death-delay-millis",
            PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS.toString(),
            "--active-reconnect-on-owner-death",
            PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH.toString(),
        )
}
