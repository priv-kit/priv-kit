package priv.kit.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivilegeCommandTest {
    @Test
    fun constructorSnapshotsMutableCollections() {
        val arguments = mutableListOf("id")
        val environment = mutableMapOf("LANG" to "C")

        val command = PrivilegeCommand(arguments, environment)
        arguments += "ignored"
        environment["LANG"] = "changed"

        assertEquals(listOf("id"), command.arguments)
        assertEquals(mapOf("LANG" to "C"), command.environment)
    }

    @Test
    fun rejectsInvalidExecutableEnvironmentAndWorkingDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeCommand(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeCommand(listOf("id"), environment = mapOf("BAD=NAME" to "value"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeCommand(listOf("id"), workingDirectory = "relative/path")
        }
    }
}
