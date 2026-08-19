package priv.kit.core.internal.file

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.util.ArrayDeque

internal object PrivilegeFileDepthFirstWalk {
    fun <Directory : Closeable, Node> walk(
        maxDepth: Int,
        openRoot: () -> Directory,
        nextNode: (Directory) -> Node?,
        isDirectory: (Node) -> Boolean,
        openDirectory: (Directory, Node) -> Directory,
    ): Flow<PrivilegeFileDepthFirstEntry<Node>> = flow {
        val frames = ArrayDeque<DirectoryFrame<Directory>>()
        frames.addLast(DirectoryFrame(openRoot(), childDepth = 1))
        try {
            while (frames.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val frame = requireNotNull(frames.peekLast())
                val node = nextNode(frame.directory)
                if (node == null) {
                    frames.removeLast().directory.close()
                    continue
                }

                emit(PrivilegeFileDepthFirstEntry(node, frame.childDepth))
                if (isDirectory(node) && frame.childDepth < maxDepth) {
                    currentCoroutineContext().ensureActive()
                    frames.addLast(
                        DirectoryFrame(
                            directory = openDirectory(frame.directory, node),
                            childDepth = frame.childDepth + 1,
                        ),
                    )
                }
            }
        } finally {
            while (frames.isNotEmpty()) {
                runCatching { frames.removeLast().directory.close() }
            }
        }
    }

    private data class DirectoryFrame<Directory>(
        val directory: Directory,
        val childDepth: Int,
    )
}

internal data class PrivilegeFileDepthFirstEntry<Node>(
    val node: Node,
    val depth: Int,
)
