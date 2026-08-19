package priv.kit.core.file

/**
 * One descendant emitted by [PrivilegeFile.walk].
 *
 * Direct children of the walked directory have [depth] 1. [metadata] is absent when the
 * Privileged Server can enumerate the name but its current Linux identity cannot read that
 * entry's attributes. Such entries are emitted but never entered during a recursive walk.
 */
@ConsistentCopyVisibility
public data class PrivilegeFileEntry internal constructor(
    public val absolutePath: String,
    public val depth: Int,
    public val metadata: PrivilegeFileMetadata?,
) {
    public val name: String
        get() = PrivilegeFilePath.name(absolutePath)
}
