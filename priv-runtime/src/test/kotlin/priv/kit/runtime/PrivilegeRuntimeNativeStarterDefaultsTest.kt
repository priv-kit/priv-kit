package priv.kit.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.core.PrivilegeProtocol

class PrivilegeRuntimeNativeStarterDefaultsTest {
    @Test
    fun nativeStarterDefaultsMatchPrivilegeProtocol() {
        val source = nativeStarterSource().readText()

        assertTrue(
            "native starter protocol version must match PrivilegeProtocol.VERSION",
            source.contains("DEFAULT_PROTOCOL_VERSION = \"${PrivilegeProtocol.VERSION}\""),
        )
        assertTrue(
            "native starter server version must match PrivilegeProtocol.SERVER_VERSION",
            source.contains("DEFAULT_SERVER_VERSION = \"${PrivilegeProtocol.SERVER_VERSION}\""),
        )
        assertTrue(
            "native starter follow-death default must match PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS",
            source.contains(
                "DEFAULT_FOLLOW_DEATH_DELAY_MILLIS = " +
                    "\"${PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS}\"",
            ),
        )
        assertTrue(
            "native starter owner-death reconnect default must match PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH",
            source.contains(
                "DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH = " +
                    "\"${PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH}\"",
            ),
        )
    }

    private fun nativeStarterSource(): File =
        listOf(
            File("src/main/cpp/privilege_runtime_starter.cpp"),
            File("priv-runtime/src/main/cpp/privilege_runtime_starter.cpp"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to find privilege_runtime_starter.cpp")
}
