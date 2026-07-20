package priv.kit.shared

import android.content.Context
import java.io.File

/** Paths in an application's Priv Kit private storage directory. */
public object PrivilegeStoragePaths {
    /** Resolves [fileName] from the application context's private files directory. */
    public fun file(context: Context, fileName: String): File =
        file(
            filesDir = context.applicationContext.filesDir,
            fileName = fileName,
        )

    public fun file(filesDir: File, fileName: String): File {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        return File(File(filesDir, STORAGE_DIRECTORY_NAME), fileName)
    }

    private const val STORAGE_DIRECTORY_NAME = ".priv-kit"
}
