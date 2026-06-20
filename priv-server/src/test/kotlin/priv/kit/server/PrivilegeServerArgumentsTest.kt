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
    fun parseAcceptsRequiredOwnerDeathConfig() {
        val config = PrivilegeServerArguments.parse(requiredArgs())

        assertEquals("classpath@1@2", config.classpathIdentity)
        assertEquals(PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS, config.followDeathDelayMillis)
        assertFalse(config.activeReconnectOnOwnerDeath)
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
    fun parseRejectsMissingOwnerDeathConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeServerArguments.parse(requiredArgsWithoutOwnerDeathConfig())
        }
    }

    private fun requiredArgs(vararg extraArgs: String): Array<String> =
        arrayOf(
            "--token",
            "token",
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
            *extraArgs,
        )

    private fun requiredArgsWithoutOwnerDeathConfig(): Array<String> =
        arrayOf(
            "--token",
            "token",
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
        )
}
