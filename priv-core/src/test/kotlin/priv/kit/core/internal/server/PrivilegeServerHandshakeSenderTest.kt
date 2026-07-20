package priv.kit.core.internal.server

import android.os.Binder
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.internal.core.PrivilegeHandshakeContract

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeServerHandshakeSenderTest {
    @Test
    fun initialHandshakeSendsLaunchIdAndClearsItFromOwnerConfig() {
        val config = PrivilegeServerConfig(
            initialLaunchId = "launch-1",
            packageName = "priv.kit.sample",
            classpath = "/data/app/priv.kit.sample/base.apk",
        )
        val ownerBinder = Binder()
        var providerArg: String? = "not-called"
        var sentLaunchId: String? = null

        val result = PrivilegeServerHandshakeSender.send(
            config = config,
            serverBinder = PrivilegeServerBinder(config),
            providerCall = { _, _, arg, extras, _ ->
                providerArg = arg
                sentLaunchId = extras.getString(
                    PrivilegeHandshakeContract.EXTRA_INITIAL_LAUNCH_ID,
                )
                Bundle().apply {
                    putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, true)
                    putBinder(PrivilegeHandshakeContract.RESULT_OWNER_BINDER, ownerBinder)
                    putString(PrivilegeHandshakeContract.RESULT_TOKEN, "owner-token")
                    putLong(
                        PrivilegeHandshakeContract.EXTRA_FOLLOW_DEATH_DELAY_MILLIS,
                        1_000L,
                    )
                    putBoolean(
                        PrivilegeHandshakeContract.EXTRA_ACTIVE_RECONNECT_ON_OWNER_DEATH,
                        false,
                    )
                }
            },
            replacementStarter = { error("replacement must not start") },
        )

        assertTrue(result.accepted)
        assertNull(providerArg)
        assertEquals("launch-1", sentLaunchId)
        assertEquals("owner-token", result.ownerConfig.token)
        assertNull(result.ownerConfig.initialLaunchId)
    }

    @Test
    fun ownerReconnectDoesNotSendInitialLaunchId() {
        val config = PrivilegeServerConfig(
            token = "owner-token",
            initialLaunchId = null,
            packageName = "priv.kit.sample",
            classpath = "/data/app/priv.kit.sample/base.apk",
        )
        var providerArg: String? = null
        var launchIdWasSent = true

        val result = PrivilegeServerHandshakeSender.send(
            config = config,
            serverBinder = PrivilegeServerBinder(config),
            providerCall = { _, _, arg, extras, _ ->
                providerArg = arg
                launchIdWasSent = extras.containsKey(
                    PrivilegeHandshakeContract.EXTRA_INITIAL_LAUNCH_ID,
                )
                Bundle().apply {
                    putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, true)
                    putBinder(PrivilegeHandshakeContract.RESULT_OWNER_BINDER, Binder())
                }
            },
            replacementStarter = { error("replacement must not start") },
        )

        assertTrue(result.accepted)
        assertEquals("owner-token", providerArg)
        assertFalse(launchIdWasSent)
        assertNull(result.ownerConfig.initialLaunchId)
    }

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
