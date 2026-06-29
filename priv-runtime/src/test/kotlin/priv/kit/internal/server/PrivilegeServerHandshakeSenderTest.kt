package priv.kit.internal.server

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.internal.core.PrivilegeHandshakeContract

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeServerHandshakeSenderTest {
    @Test
    fun rejectedHandshakeStartsReplacementCommand() {
        val config = PrivilegeServerConfig(
            token = "token-1",
            packageName = "priv.kit.sample",
            classpath = "/data/app/priv.kit.sample-old/base.apk",
        )
        val startedCommands = mutableListOf<String>()

        val result = PrivilegeServerHandshakeSender.send(
            config = config,
            serverBinder = PrivilegeServerBinder(config),
            providerCall = { _, _, _, _, _ ->
                Bundle().apply {
                    putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, false)
                    putString(
                        PrivilegeHandshakeContract.RESULT_REPLACEMENT_COMMAND,
                        "/data/app/priv.kit.sample-current/lib/arm64/libprivkitstarter.so",
                    )
                }
            },
            replacementStarter = { commandLine ->
                startedCommands += commandLine
            },
        )

        assertFalse(result.accepted)
        assertTrue(result.replacementStarted)
        assertEquals(
            listOf("/data/app/priv.kit.sample-current/lib/arm64/libprivkitstarter.so"),
            startedCommands,
        )
    }
}
