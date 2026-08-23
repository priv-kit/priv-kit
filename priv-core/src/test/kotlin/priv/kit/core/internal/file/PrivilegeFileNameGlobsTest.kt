package priv.kit.core.internal.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeFileNameGlobsTest {
    @Test
    fun matchesCompleteDirectoryNamesWithSimpleWildcards() {
        val globs = PrivilegeFileNameGlobs.compile(
            listOf("node_modules", "build-*", "cache?"),
        )

        assertTrue(globs.matches("node_modules"))
        assertTrue(globs.matches("build-debug"))
        assertTrue(globs.matches("cache1"))
        assertTrue(globs.matches("cache\uD83D\uDE00"))
        assertFalse(globs.matches("nested-node_modules"))
        assertFalse(globs.matches("Node_Modules"))
        assertFalse(globs.matches("cache-long"))
    }

    @Test
    fun escapedWildcardsAndBackslashesAreMatchedLiterally() {
        val globs = PrivilegeFileNameGlobs.compile(
            listOf("literal\\*", "question\\?", "dir\\\\name"),
        )

        assertTrue(globs.matches("literal*"))
        assertTrue(globs.matches("question?"))
        assertTrue(globs.matches("dir\\name"))
        assertFalse(globs.matches("literal-anything"))
    }

    @Test
    fun rejectsPathPatternsAndInvalidEscapes() {
        listOf(
            "parent/child",
            "trailing\\",
            "unsupported\\x",
            "nul\u0000name",
        ).forEach { pattern ->
            assertThrows(IllegalArgumentException::class.java) {
                PrivilegeFileNameGlobs.validate(listOf(pattern))
            }
        }
    }
}
