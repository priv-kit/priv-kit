package priv.kit.core.internal.file

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream

class PrivilegeFileRecursiveDeleteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deletesNestedTreeAndTreatsMissingTargetAsSuccess() = runBlocking {
        assumeTrue(providerSupportsSecureDirectoryStream())
        val tree = temporaryFolder.newFolder("tree").toPath()
        val nested = Files.createDirectories(tree.resolve("first/second"))
        Files.write(nested.resolve("payload.txt"), byteArrayOf(1, 2, 3))
        Files.write(tree.resolve("root.txt"), byteArrayOf(4, 5, 6))

        assertTrue(PrivilegeFileRecursiveDelete.delete(tree.toString()))
        assertFalse(Files.exists(tree))
        assertTrue(PrivilegeFileRecursiveDelete.delete(tree.toString()))
    }

    @Test
    fun deletesSymbolicLinkWithoutFollowingItsTarget() = runBlocking {
        assumeTrue(providerSupportsSecureDirectoryStream())
        val target = temporaryFolder.newFolder("target").toPath()
        val payload = Files.write(target.resolve("payload.txt"), byteArrayOf(1))
        val tree = temporaryFolder.newFolder("tree-with-link").toPath()
        try {
            Files.createSymbolicLink(tree.resolve("target-link"), target)
        } catch (exception: Exception) {
            assumeNoException(exception)
        }

        assertTrue(PrivilegeFileRecursiveDelete.delete(tree.toString()))
        assertTrue(Files.exists(target))
        assertTrue(Files.exists(payload))
    }

    @Test
    fun unsupportedSecureDirectoryProviderFailsBeforeMutatingDirectory() = runBlocking {
        val tree = temporaryFolder.newFolder("unsupported-tree").toPath()
        Files.write(tree.resolve("payload.txt"), byteArrayOf(1))
        Files.newDirectoryStream(tree.parent).use { stream ->
            assumeTrue(stream !is SecureDirectoryStream<*>)
        }

        assertFalse(PrivilegeFileRecursiveDelete.delete(tree.toString()))
        assertTrue(Files.exists(tree.resolve("payload.txt")))
    }

    private fun providerSupportsSecureDirectoryStream(): Boolean {
        val directory = temporaryFolder.root.toPath()
        return Files.newDirectoryStream(directory).use { stream ->
            stream is SecureDirectoryStream<*>
        }
    }
}
