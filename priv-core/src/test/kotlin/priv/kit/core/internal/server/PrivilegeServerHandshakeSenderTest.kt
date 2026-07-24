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
import priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeServerHandshakeSenderTest {
    @Test
    fun initialHandshakeSendsLaunchCorrelationIdAndClearsItFromOwnerConfig() {
        val config = PrivilegeServerConfig(
            launchCorrelationId = "launch-1",
            packageName = "priv.kit.sample",
            classpath = "/data/app/priv.kit.sample/base.apk",
        )
        val ownerBinder = Binder()
        var providerArg: String? = "not-called"
        var sentCorrelationId: String? = null
        var ownerReconnect = true

        val result = PrivilegeServerHandshakeSender.send(
            config = config,
            serverBinder = PrivilegeServerBinder(config),
            origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
            providerCall = { _, _, arg, extras, _ ->
                providerArg = arg
                sentCorrelationId = extras.getString(
                    PrivilegeHandshakeContract.EXTRA_LAUNCH_CORRELATION_ID,
                )
                ownerReconnect = extras.getBoolean(
                    PrivilegeHandshakeContract.EXTRA_OWNER_RECONNECT,
                )
                Bundle().apply {
                    putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, true)
                    putBinder(PrivilegeHandshakeContract.RESULT_OWNER_BINDER, ownerBinder)
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
        assertEquals("launch-1", sentCorrelationId)
        assertFalse(ownerReconnect)
        assertNull(result.ownerConfig.launchCorrelationId)
    }

    @Test
    fun ownerReconnectDoesNotSendLaunchCorrelationId() {
        val config = PrivilegeServerConfig(
            launchCorrelationId = null,
            packageName = "priv.kit.sample",
            classpath = "/data/app/priv.kit.sample/base.apk",
        )
        var providerArg: String? = "not-called"
        var correlationIdWasSent = true
        var ownerReconnect = false

        val result = PrivilegeServerHandshakeSender.send(
            config = config,
            serverBinder = PrivilegeServerBinder(config),
            origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
            providerCall = { _, _, arg, extras, _ ->
                providerArg = arg
                correlationIdWasSent = extras.containsKey(
                    PrivilegeHandshakeContract.EXTRA_LAUNCH_CORRELATION_ID,
                )
                ownerReconnect = extras.getBoolean(
                    PrivilegeHandshakeContract.EXTRA_OWNER_RECONNECT,
                )
                Bundle().apply {
                    putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, true)
                    putBinder(PrivilegeHandshakeContract.RESULT_OWNER_BINDER, Binder())
                }
            },
            replacementStarter = { error("replacement must not start") },
        )

        assertTrue(result.accepted)
        assertNull(providerArg)
        assertTrue(ownerReconnect)
        assertFalse(correlationIdWasSent)
        assertNull(result.ownerConfig.launchCorrelationId)
    }

    @Test
    fun rejectedHandshakeStartsReplacementCommand() {
        val config = PrivilegeServerConfig(
            packageName = "priv.kit.sample",
            classpath = "/data/app/priv.kit.sample-old/base.apk",
        )
        val startedCommands = mutableListOf<String>()

        val result = PrivilegeServerHandshakeSender.send(
            config = config,
            serverBinder = PrivilegeServerBinder(config),
            origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
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
