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

    @Test
    fun prunedDirectoryIsEmittedWithoutBeingOpenedAndWalkContinues() = runBlocking {
        val skipped = FakeDirectory(FakeNode("hidden"))
        val entered = FakeDirectory(FakeNode("visible"))
        val root = FakeDirectory(
            FakeNode("skip", skipped),
            FakeNode("enter", entered),
            FakeNode("sibling"),
        )

        val entries = flow(
            root = root,
            maxDepth = Int.MAX_VALUE,
            shouldEnter = { node -> node.name != "skip" },
        ).toList()

        assertEquals(
            listOf("skip", "enter", "visible", "sibling"),
            entries.map { it.node.name },
        )
        assertFalse(skipped.opened)
        assertFalse(skipped.closed)
        assertTrue(entered.opened)
        assertTrue(entered.closed)
        assertTrue(root.closed)
    }

    private suspend fun walk(
        root: FakeDirectory,
        maxDepth: Int,
    ): List<PrivilegeFileDepthFirstEntry<FakeNode>> = flow(root, maxDepth).toList()

    private fun flow(
        root: FakeDirectory,
        maxDepth: Int,
        shouldEnter: (FakeNode) -> Boolean = { true },
    ) =
        PrivilegeFileDepthFirstWalk.walk(
            maxDepth = maxDepth,
            openRoot = {
                root.opened = true
                root
            },
            nextNode = FakeDirectory::next,
            isDirectory = { node -> node.directory != null },
            shouldEnter = shouldEnter,
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
