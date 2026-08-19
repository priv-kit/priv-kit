package priv.kit.core.internal.file

internal enum class PrivilegeFileWalkStatFailureAction {
    SKIP_ENTRY,
    EMIT_NAME_ONLY,
    FAIL_WALK,
}

internal fun classifyPrivilegeFileWalkStatFailure(
    errno: Int,
    noEntryErrno: Int,
    accessDeniedErrno: Int,
    operationNotPermittedErrno: Int,
): PrivilegeFileWalkStatFailureAction = when (errno) {
    noEntryErrno -> PrivilegeFileWalkStatFailureAction.SKIP_ENTRY
    accessDeniedErrno,
    operationNotPermittedErrno,
    -> PrivilegeFileWalkStatFailureAction.EMIT_NAME_ONLY
    else -> PrivilegeFileWalkStatFailureAction.FAIL_WALK
}
