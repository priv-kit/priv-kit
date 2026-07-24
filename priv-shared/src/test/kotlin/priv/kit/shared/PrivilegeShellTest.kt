package priv.kit.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeShellTest {
    @Test
    fun quotesOnlyUnsafeArguments() {
        assertEquals(
            "/data/app/priv-kit.so",
            "/data/app/priv-kit.so".toPrivilegeShellArgument(),
        )
        assertEquals("''", "".toPrivilegeShellArgument())
        assertEquals("'app id'", "app id".toPrivilegeShellArgument())
        assertEquals(
            "'app'\"'\"'s path'",
            "app's path".toPrivilegeShellArgument(),
        )
    }
}
