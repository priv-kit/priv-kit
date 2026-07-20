package priv.kit.shared

import android.app.Application
import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeStoragePathsAndroidTest {
    @Test
    fun resolvesFilesFromTheApplicationContext() {
        val application = RuntimeEnvironment.getApplication() as Application
        val context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = error("wrapper filesDir must not be used")
        }

        assertEquals(
            File(File(application.filesDir, ".priv-kit"), "state"),
            PrivilegeStoragePaths.file(context = context, fileName = "state"),
        )
    }
}
