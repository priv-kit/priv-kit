package priv.kit.core.file

import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

internal interface PrivilegeFileOperations {
    fun query(path: String, kind: Int): Boolean

    fun queryLong(path: String, kind: Int): Long

    fun metadata(path: String, followSymbolicLinks: Boolean): PrivilegeFileMetadata

    fun openInputStream(path: String): InputStream

    fun openOutputStream(path: String, append: Boolean, syncOnClose: Boolean): OutputStream

    fun createNewFile(path: String): Boolean

    fun mkdir(path: String): Boolean

    fun mkdirs(path: String): Boolean

    fun delete(path: String): Boolean

    suspend fun deleteRecursively(path: String): Boolean

    fun renameTo(sourcePath: String, targetPath: String): Boolean

    fun replaceAtomically(sourcePath: String, targetPath: String)

    fun scanDirectory(path: String): Flow<PrivilegeFileDirectoryEntry>
}
