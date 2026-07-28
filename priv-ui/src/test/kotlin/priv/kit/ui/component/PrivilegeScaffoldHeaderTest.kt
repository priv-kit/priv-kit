package priv.kit.ui.component

import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeScaffoldHeaderTest {
    @Test
    fun githubActionOpensThePrivKitRepository() {
        var openedUri: String? = null
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUri = uri
            }
        }

        uriHandler.openPrivilegeUiGitHubRepository()

        assertEquals("https://github.com/priv-kit/priv-kit", openedUri)
    }

    @Test
    fun githubActionDoesNotCrashWhenNoUriHandlerIsAvailable() {
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                error("No URI handler")
            }
        }

        uriHandler.openPrivilegeUiGitHubRepository()
    }
}
