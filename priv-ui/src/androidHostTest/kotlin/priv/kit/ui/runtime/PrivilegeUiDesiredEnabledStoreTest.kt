package priv.kit.ui.runtime

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiDesiredEnabledStoreTest {
    private val application = RuntimeEnvironment.getApplication()
    private val file = File(application.filesDir, ".priv-kit/ui-desired-enabled")
    private val temporaryFile = File(application.filesDir, ".priv-kit/ui-desired-enabled.tmp")

    @Before
    fun setUp() {
        file.delete()
        temporaryFile.delete()
    }

    @After
    fun tearDown() {
        file.delete()
        temporaryFile.delete()
    }

    @Test
    fun missingAndInvalidValuesAreDisabled() {
        val store = PrivilegeUiDesiredEnabledStore(application)
        assertFalse(store.read())

        file.parentFile?.mkdirs()
        file.writeText("true", StandardCharsets.UTF_8)

        assertFalse(store.read())
    }

    @Test
    fun writesExactlyOneOrZero() {
        val store = PrivilegeUiDesiredEnabledStore(application)

        store.write(true)
        assertEquals("1", file.readText(StandardCharsets.UTF_8))
        assertTrue(store.read())

        store.write(false)
        assertEquals("0", file.readText(StandardCharsets.UTF_8))
        assertFalse(store.read())
        assertFalse(temporaryFile.exists())
    }
}
