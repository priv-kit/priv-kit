package priv.kit.core.userservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUserServiceEnvironmentTest {
    @Test
    fun serverProcessPropertyRecognizesOnlyMarkedValue() {
        assertTrue(
            PrivilegeUserServiceEnvironment.isServerProcessProperty(
                PrivilegeUserServiceEnvironment.SERVER_PROCESS_PROPERTY_VALUE,
            ),
        )
        assertFalse(PrivilegeUserServiceEnvironment.isServerProcessProperty(null))
        assertFalse(PrivilegeUserServiceEnvironment.isServerProcessProperty("false"))
    }

    @Test
    fun markServerProcessSetsProcessProperty() {
        val key = PrivilegeUserServiceEnvironment.SERVER_PROCESS_PROPERTY
        val previousValue = System.getProperty(key)
        try {
            System.clearProperty(key)

            PrivilegeUserServiceEnvironment.markServerProcess()

            assertEquals(
                PrivilegeUserServiceEnvironment.SERVER_PROCESS_PROPERTY_VALUE,
                System.getProperty(key),
            )
        } finally {
            if (previousValue == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previousValue)
            }
        }
    }
}
