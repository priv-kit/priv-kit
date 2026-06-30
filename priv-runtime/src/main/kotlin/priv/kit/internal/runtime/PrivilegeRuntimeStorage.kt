package priv.kit.internal.runtime

import java.io.File

internal object PrivilegeRuntimeStorage {
    fun file(fileName: String): File {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        return File(directory(), fileName)
    }

    private fun directory(): File =
        File(PrivilegeRuntimeContext.require().filesDir, STORAGE_DIRECTORY)

    private const val STORAGE_DIRECTORY = ".priv-kit"
}
