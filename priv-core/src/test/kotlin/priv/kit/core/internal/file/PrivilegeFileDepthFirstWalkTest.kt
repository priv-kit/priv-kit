package priv.kit.core.internal.file

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class PrivilegeFileDepthFirstWalkTest {
    @Test
    fun walkIsDepthFirstPreOrderAndHonorsMaxDepth() = runBlocking {
        val nested = FakeDirectory(FakeNode("nested"))
        val branch = FakeDirectory(FakeNode("leaf"), FakeNode("nested-dir", nested))
        val root = FakeDirectory(FakeNode("branch", branch), FakeNode("sibling"))

        val entries = walk(root, maxDepth = 3)

        assertEquals(
            listOf(
                "branch" to 1,
                "leaf" to 2,
                "nested-dir" to 2,
                "nested" to 3,
                "sibling" to 1,
            ),
            entries.map { it.node.name to it.depth },
        )
        assertTrue(root.closed)
        assertTrue(branch.closed)
        assertTrue(nested.closed)

        val shallowBranch = FakeDirectory(FakeNode("hidden"))
        val shallowRoot = FakeDirectory(
            FakeNode("branch", shallowBranch),
            FakeNode("sibling"),
        )
        assertEquals(
            listOf("branch" to 1, "sibling" to 1),
            walk(shallowRoot, maxDepth = 1).map { it.node.name to it.depth },
        )
        assertTrue(shallowRoot.closed)
        assertFalse(shallowBranch.opened)
        assertFalse(shallowBranch.closed)
    }

    @Test
    fun downstreamCancellationClosesOpenDirectoriesWithoutEnteringTheEmittedNode() = runBlocking {
        val branch = FakeDirectory(FakeNode("child"))
        val root = FakeDirectory(FakeNode("branch", branch), FakeNode("sibling"))

        val first = flow(root, maxDepth = Int.MAX_VALUE).take(1).toList()

        assertEquals(listOf("branch"), first.map { it.node.name })
        assertTrue(root.closed)
        assertFalse(branch.opened)
        assertFalse(branch.closed)
    }

    private suspend fun walk(
        root: FakeDirectory,
        maxDepth: Int,
    ): List<PrivilegeFileDepthFirstEntry<FakeNode>> = flow(root, maxDepth).toList()

    private fun flow(root: FakeDirectory, maxDepth: Int) =
        PrivilegeFileDepthFirstWalk.walk(
            maxDepth = maxDepth,
            openRoot = {
                root.opened = true
                root
            },
            nextNode = FakeDirectory::next,
            isDirectory = { node -> node.directory != null },
            openDirectory = { _, node ->
                requireNotNull(node.directory).also { it.opened = true }
            },
        )

    private data class FakeNode(
        val name: String,
        val directory: FakeDirectory? = null,
    )

    private class FakeDirectory(
        vararg nodes: FakeNode,
    ) : Closeable {
        private val iterator = nodes.iterator()
        var opened: Boolean = false
        var closed: Boolean = false

        fun next(): FakeNode? = if (iterator.hasNext()) iterator.next() else null

        override fun close() {
            check(!closed) { "Directory was closed twice" }
            closed = true
        }
    }
}
