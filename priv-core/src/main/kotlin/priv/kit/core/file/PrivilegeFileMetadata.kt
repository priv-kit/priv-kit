package priv.kit.core.file

/** The filesystem object type reported by the Privileged Server. */
public enum class PrivilegeFileType {
    REGULAR_FILE,
    DIRECTORY,
    SYMBOLIC_LINK,
    BLOCK_DEVICE,
    CHARACTER_DEVICE,
    FIFO,
    SOCKET,
    OTHER,
}

/** A single, uncached metadata snapshot read by the Privileged Server. */
public data class PrivilegeFileMetadata public constructor(
    public val absolutePath: String,
    public val sizeBytes: Long,
    public val lastModifiedMillis: Long,
    public val unixMode: Int,
    public val uid: Int,
    public val gid: Int,
) {
    public val name: String
        get() = PrivilegeFilePath.name(absolutePath)

    public val type: PrivilegeFileType
        get() = unixMode.toPrivilegeFileType()
}

private fun Int.toPrivilegeFileType(): PrivilegeFileType = when (this and UNIX_TYPE_MASK) {
    UNIX_TYPE_REGULAR_FILE -> PrivilegeFileType.REGULAR_FILE
    UNIX_TYPE_DIRECTORY -> PrivilegeFileType.DIRECTORY
    UNIX_TYPE_SYMBOLIC_LINK -> PrivilegeFileType.SYMBOLIC_LINK
    UNIX_TYPE_BLOCK_DEVICE -> PrivilegeFileType.BLOCK_DEVICE
    UNIX_TYPE_CHARACTER_DEVICE -> PrivilegeFileType.CHARACTER_DEVICE
    UNIX_TYPE_FIFO -> PrivilegeFileType.FIFO
    UNIX_TYPE_SOCKET -> PrivilegeFileType.SOCKET
    else -> PrivilegeFileType.OTHER
}

private const val UNIX_TYPE_MASK: Int = 0xf000
private const val UNIX_TYPE_SOCKET: Int = 0xc000
private const val UNIX_TYPE_SYMBOLIC_LINK: Int = 0xa000
private const val UNIX_TYPE_REGULAR_FILE: Int = 0x8000
private const val UNIX_TYPE_BLOCK_DEVICE: Int = 0x6000
private const val UNIX_TYPE_DIRECTORY: Int = 0x4000
private const val UNIX_TYPE_CHARACTER_DEVICE: Int = 0x2000
private const val UNIX_TYPE_FIFO: Int = 0x1000
