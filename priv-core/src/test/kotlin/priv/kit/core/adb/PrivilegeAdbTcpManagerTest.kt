package priv.kit.core.adb

import android.net.nsd.NsdManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeStartupException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeAdbTcpManagerTest {
    @After
    fun clearProperties() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "")
    }

    @Test
    fun switchToTcpSkipsCommandWhenTargetPortIsAlreadyActive() = runBlocking {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "5555")
        val manager = manager(
            loadKeyBytes = { error("key should not be loaded when TCP port is already active") },
            nsdManagerProvider = { error("NSD should not be used when TCP port is already active") },
        )

        val result = manager.switchToTcp(
            currentPort = null,
            tcpPort = 5555,
            options = null,
        )

        assertEquals(5555, result.port)
        assertTrue(result.outputText.contains("ADB TCP port 5555 is already active"))
    }

    @Test
    fun openTcpAuthorizationCheckSessionWrapsKeyFailureInPublicStartupException() {
        val keyFailure = IllegalStateException("key storage is unavailable")
        val manager = manager(
            loadKeyBytes = { throw keyFailure },
            nsdManagerProvider = { error("NSD should not be used when loading the ADB key") },
        )

        val exception = assertThrows(PrivilegeStartupException::class.java) {
            manager.openTcpAuthorizationCheckSession(
                tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            )
        }

        assertEquals(PrivilegeStartupException::class.java, exception.javaClass)
        assertEquals("Failed to open ADB TCP authorization check session", exception.message)
        assertSame(keyFailure, exception.cause?.cause)
    }

    private fun manager(
        loadKeyBytes: () -> ByteArray,
        nsdManagerProvider: () -> NsdManager,
    ): PrivilegeAdbTcpManager {
        val identityProvider = PrivilegeAdbIdentityProvider(
            identity = PrivilegeAdbIdentity.default(
                deviceName = PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME,
            ),
            loadKeyBytes = loadKeyBytes,
        )
        val wirelessDebuggingControllerProvider = { fakeWirelessDebuggingController() }
        val endpointResolver = PrivilegeAdbEndpointResolver(
            nsdManagerProvider = nsdManagerProvider,
            wirelessDebuggingControllerProvider = wirelessDebuggingControllerProvider,
        )
        return PrivilegeAdbTcpManager(
            identityProvider = identityProvider,
            endpointResolver = endpointResolver,
            wirelessDebuggingControllerProvider = wirelessDebuggingControllerProvider,
        )
    }

    private fun fakeWirelessDebuggingController(): PrivilegeAdbWirelessDebuggingController =
        object : PrivilegeAdbWirelessDebuggingController {
            override fun status(): PrivilegeAdbWirelessDebuggingControlStatus =
                PrivilegeAdbWirelessDebuggingControlStatus(
                    supported = true,
                    permissionDeclared = true,
                    permissionGranted = false,
                    wirelessDebuggingEnabled = false,
                    canManage = false,
                )

            override fun enableAdb() = Unit

            override fun prepareAdb() = Unit

            override fun setWirelessDebuggingEnabled(enabled: Boolean) = Unit
        }

    private fun setSystemProperty(key: String, value: String) {
        systemPropertiesClass
            .getDeclaredMethod("set", String::class.java, String::class.java)
            .invoke(null, key, value)
    }

    private companion object {
        private const val SERVICE_ADB_TCP_PORT = "service.adb.tcp.port"
        private const val PERSIST_ADB_TCP_PORT = "persist.adb.tcp.port"
        private val systemPropertiesClass = Class.forName("android.os.SystemProperties")
    }
}
