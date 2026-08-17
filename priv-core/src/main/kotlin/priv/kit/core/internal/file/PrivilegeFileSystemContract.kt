package priv.kit.core.internal.file

internal object PrivilegeFileSystemContract {
    const val QUERY_EXISTS: Int = 1
    const val QUERY_IS_FILE: Int = 2
    const val QUERY_IS_DIRECTORY: Int = 3
    const val QUERY_CAN_READ: Int = 5
    const val QUERY_CAN_WRITE: Int = 6
    const val QUERY_CAN_EXECUTE: Int = 7
    const val QUERY_IS_SYMBOLIC_LINK: Int = 8

    const val QUERY_LENGTH: Int = 1
    const val QUERY_LAST_MODIFIED: Int = 2

    const val STAT_MODE: Int = 0
    const val STAT_SIZE: Int = 1
    const val STAT_LAST_MODIFIED_SECONDS: Int = 2
    const val STAT_UID: Int = 3
    const val STAT_GID: Int = 4
    const val STAT_FIELD_COUNT: Int = 5

    const val SCAN_ENTRY: Int = 1
    const val SCAN_COMPLETE: Int = 2
    const val SCAN_ERROR: Int = 3

    const val MAX_CONCURRENT_SCANS: Int = 4
    const val MAX_CONCURRENT_TRANSFERS: Int = 4
    const val CREATE_MODE: Int = 0x180 // 0600
}
