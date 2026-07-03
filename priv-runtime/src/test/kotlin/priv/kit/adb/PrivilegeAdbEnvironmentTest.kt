package priv.kit.adb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeAdbEnvironmentTest {
    @After
    fun clearProperties() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "")
    }

    @Test
    fun serviceDisabledOverridesPersistedTcpPort() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "-1")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "5555")

        assertEquals(-1, PrivilegeAdbEnvironment.getAdbTcpPort())
    }

    @Test
    fun blankServiceFallsBackToPersistedTcpPort() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "5555")

        assertEquals(5555, PrivilegeAdbEnvironment.getAdbTcpPort())
    }

    @Test
    fun activeServiceTcpPortWins() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "5555")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "4444")

        assertEquals(5555, PrivilegeAdbEnvironment.getAdbTcpPort())
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
