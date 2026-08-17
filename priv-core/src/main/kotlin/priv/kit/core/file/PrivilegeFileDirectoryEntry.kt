package priv.kit.core.file

/**
 * One name enumerated by [PrivilegeFile.scanDirectory].
 *
 * [metadata] is absent when the Privileged Server can enumerate the name but its current Linux
 * identity cannot read that entry's attributes. Callers can still present [absolutePath] and
 * [name], then retry an explicit [PrivilegeFile.metadata] operation if the user selects it.
 */
public data class PrivilegeFileDirectoryEntry public constructor(
    public val absolutePath: String,
    public val metadata: PrivilegeFileMetadata?,
) {
    init {
        PrivilegeFilePath.validateAbsolute(absolutePath)
        require(metadata == null || metadata.absolutePath == absolutePath) {
            "Directory entry metadata path must match $absolutePath"
        }
    }

    public val name: String
        get() = PrivilegeFilePath.name(absolutePath)
}
