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
    fun disabledServiceKeepsPersistedPortAsConfiguredCandidate() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "-1")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "5555")

        assertEquals(-1, PrivilegeAdbEnvironment.getActiveAdbTcpPort())
        assertEquals(5555, PrivilegeAdbEnvironment.getConfiguredAdbTcpPort())
    }

    @Test
    fun blankServiceFallsBackToPersistedTcpPort() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "5555")

        assertEquals(5555, PrivilegeAdbEnvironment.getActiveAdbTcpPort())
        assertEquals(5555, PrivilegeAdbEnvironment.getConfiguredAdbTcpPort())
    }

    @Test
    fun activeServiceTcpPortWins() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "5555")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "4444")

        assertEquals(5555, PrivilegeAdbEnvironment.getActiveAdbTcpPort())
        assertEquals(5555, PrivilegeAdbEnvironment.getConfiguredAdbTcpPort())
    }

    @Test
    fun malformedServiceFallsBackOnlyForConfiguredPort() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "invalid")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "5555")

        assertEquals(-1, PrivilegeAdbEnvironment.getActiveAdbTcpPort())
        assertEquals(5555, PrivilegeAdbEnvironment.getConfiguredAdbTcpPort())
    }

    @Test
    fun zeroServiceDoesNotFallBackToPersistedPort() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "0")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "5555")

        assertEquals(-1, PrivilegeAdbEnvironment.getActiveAdbTcpPort())
        assertEquals(-1, PrivilegeAdbEnvironment.getConfiguredAdbTcpPort())
    }

    @Test
    fun missingOrMalformedPersistedPortIsUnavailable() {
        assertEquals(-1, PrivilegeAdbEnvironment.getActiveAdbTcpPort())
        assertEquals(-1, PrivilegeAdbEnvironment.getConfiguredAdbTcpPort())

        setSystemProperty(PERSIST_ADB_TCP_PORT, "invalid")

        assertEquals(-1, PrivilegeAdbEnvironment.getActiveAdbTcpPort())
        assertEquals(-1, PrivilegeAdbEnvironment.getConfiguredAdbTcpPort())
    }

    @Test
    fun outOfRangePortsAreUnavailable() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "65536")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "5555")

        assertEquals(-1, PrivilegeAdbEnvironment.getActiveAdbTcpPort())
        assertEquals(-1, PrivilegeAdbEnvironment.getConfiguredAdbTcpPort())

        setSystemProperty(SERVICE_ADB_TCP_PORT, "-1")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "65536")

        assertEquals(-1, PrivilegeAdbEnvironment.getActiveAdbTcpPort())
        assertEquals(-1, PrivilegeAdbEnvironment.getConfiguredAdbTcpPort())
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
