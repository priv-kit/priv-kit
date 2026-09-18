package priv.kit.core.internal.file

import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.flow.flowOf
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
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

@RunWith(RobolectricTestRunner::class)
class PrivilegeFileTreeWalkerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writeEntriesFlushesFirstEntryThenConfiguredBatchesAndCompletion() = runBlocking {
        val sink = RecordingOutputStream()
        val output = DataOutputStream(BufferedOutputStream(sink))
        val entries = flowOf(
            PrivilegeFileWalkRecord("/tree/one", 1, null),
            PrivilegeFileWalkRecord("/tree/two", 1, null),
            PrivilegeFileWalkRecord("/tree/three", 1, null),
            PrivilegeFileWalkRecord("/tree/four", 1, null),
        )

        PrivilegeFileTreeWalker.writeEntries(entries, flushBatchSize = 2, output)

        assertEquals(3, sink.flushSnapshots.size)
        assertEquals(1, countEntryFrames(sink.flushSnapshots[0]))
        assertEquals(3, countEntryFrames(sink.flushSnapshots[1]))
        assertEquals(4, countEntryFrames(sink.flushSnapshots[2]))
    }

    private fun countEntryFrames(bytes: ByteArray): Int {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        var count = 0
        while (input.available() > 0) {
            when (input.readUnsignedByte()) {
                PrivilegeFileSystemContract.WALK_ENTRY -> {
                    PrivilegeFileWire.readEntry(input)
                    count += 1
                }
                PrivilegeFileSystemContract.WALK_COMPLETE -> {
                    assertEquals(0, input.available())
                    return count
                }
                else -> error("Unexpected walk frame")
            }
        }
        return count
    }

    private class RecordingOutputStream : ByteArrayOutputStream() {
        val flushSnapshots = mutableListOf<ByteArray>()

        override fun flush() {
            super.flush()
            flushSnapshots += toByteArray()
        }
    }

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
    fun walkEmitsButDoesNotEnterDirectoriesMatchingNameGlobs() = runBlocking {
        val root = temporaryFolder.newFolder("root-with-skipped-directories")
        val nodeModules = root.resolve("node_modules").apply { mkdir() }
        val dependency = nodeModules.resolve("dependency.js").apply { writeText("dependency") }
        val buildCache = root.resolve("build-cache").apply { mkdir() }
        val cached = buildCache.resolve("cached.bin").apply { writeText("cached") }
        val source = root.resolve("source").apply { mkdir() }
        val sourceFile = source.resolve("main.kt").apply { writeText("source") }
        Files.newDirectoryStream(root.toPath()).use { stream ->
            assumeTrue(stream is SecureDirectoryStream<*>)
        }

        val entries = PrivilegeFileTreeWalker.walk(
            path = root.absolutePath,
            maxDepth = 3,
            skipDirectoryGlobs = listOf("node_modules", "build-*"),
        ).toList()
        val paths = entries.map { it.absolutePath }.toSet()

        assertTrue(nodeModules.absolutePath in paths)
        assertTrue(buildCache.absolutePath in paths)
        assertTrue(sourceFile.absolutePath in paths)
        assertFalse(dependency.absolutePath in paths)
        assertFalse(cached.absolutePath in paths)
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
