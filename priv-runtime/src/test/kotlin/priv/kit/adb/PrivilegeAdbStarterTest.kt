package priv.kit.adb

import android.net.nsd.NsdManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeAdbStarterTest {
    @After
    fun clearProperties() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "")
    }

    @Test
    fun switchToTcpSkipsCommandWhenTargetPortIsAlreadyActive() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "5555")
        val starter = starter(
            loadKeyBytes = { error("key should not be loaded when TCP port is already active") },
            nsdManagerProvider = { error("NSD should not be used when TCP port is already active") },
        )

        val result = starter.switchToTcp(tcpPort = 5555)

        assertEquals(5555, result.port)
        assertTrue(result.outputText.contains("ADB TCP port 5555 is already active"))
    }

    private fun starter(
        loadKeyBytes: () -> ByteArray = { ByteArray(0) },
        nsdManagerProvider: () -> NsdManager,
    ): PrivilegeAdbStarter {
        val constructor = PrivilegeAdbStarter::class.java.getDeclaredConstructor(
            PrivilegeAdbIdentity::class.java,
            Function0::class.java,
            Function0::class.java,
            Function0::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            PrivilegeAdbIdentity.default(),
            loadKeyBytes,
            nsdManagerProvider,
            { fakeWirelessDebuggingController() },
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
