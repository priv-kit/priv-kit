package priv.kit.internal.runtime

import java.io.File
import priv.kit.shared.PrivilegeStoragePaths

internal object PrivilegeStorage {
    fun file(fileName: String): File =
        PrivilegeStoragePaths.file(
            context = PrivilegeContext.require(),
            fileName = fileName,
        )
}
