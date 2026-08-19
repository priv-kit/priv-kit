package priv.kit.core.internal.file

import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream

@RunWith(RobolectricTestRunner::class)
class PrivilegeFileTreeWalkerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun walkEmitsDepthFirstPreOrderAndHonorsMaxDepth() = runBlocking {
        val root = temporaryFolder.newFolder("root")
        val branch = root.resolve("branch").apply { mkdir() }
        val nested = branch.resolve("nested.txt").apply { writeText("nested") }
        val sibling = root.resolve("sibling.txt").apply { writeText("sibling") }

        Files.newDirectoryStream(root.toPath()).use { stream ->
            assumeTrue(stream is SecureDirectoryStream<*>)
        }

        val entries = PrivilegeFileTreeWalker.walk(root.absolutePath, maxDepth = 2).toList()
        val branchIndex = entries.indexOfFirst { it.absolutePath == branch.absolutePath }
        val nestedIndex = entries.indexOfFirst { it.absolutePath == nested.absolutePath }
        val siblingIndex = entries.indexOfFirst { it.absolutePath == sibling.absolutePath }

        assertTrue(branchIndex >= 0)
        assertEquals(branchIndex + 1, nestedIndex)
        assertTrue(siblingIndex >= 0)
        assertEquals(1, entries[branchIndex].depth)
        assertEquals(2, entries[nestedIndex].depth)
        assertEquals(1, entries[siblingIndex].depth)

        val shallow = PrivilegeFileTreeWalker.walk(root.absolutePath, maxDepth = 1).toList()
        assertEquals(setOf(branch.absolutePath, sibling.absolutePath), shallow.map {
            it.absolutePath
        }.toSet())
        assertTrue(shallow.all { it.depth == 1 })
    }

    @Test
    fun walkEmitsButDoesNotEnterSymbolicLinks() = runBlocking {
        val root = temporaryFolder.newFolder("root-with-link")
        val target = temporaryFolder.newFolder("link-target")
        val targetFile = target.resolve("target.txt").apply { writeText("target") }
        val link = root.resolve("link")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (exception: Exception) {
            assumeNoException(exception)
        }
        Files.newDirectoryStream(root.toPath()).use { stream ->
            assumeTrue(stream is SecureDirectoryStream<*>)
        }

        val entries = PrivilegeFileTreeWalker.walk(root.absolutePath, maxDepth = 3).toList()
        val linkEntry = requireNotNull(entries.singleOrNull { it.absolutePath == link.absolutePath })

        assertTrue(OsConstants.S_ISLNK(requireNotNull(linkEntry.stat).st_mode))
        assertFalse(entries.any { it.absolutePath == link.resolve(targetFile.name).absolutePath })
    }

    @Test
    fun walkRejectsARegularFileAsTheRoot() = runBlocking {
        val file = temporaryFolder.newFile("root-file")

        val failure = runCatching {
            PrivilegeFileTreeWalker.walk(file.absolutePath, maxDepth = 1).toList()
        }.exceptionOrNull()

        assertTrue(failure is ErrnoException)
        assertEquals(OsConstants.ENOTDIR, (failure as ErrnoException).errno)
    }
}
