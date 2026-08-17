package priv.kit.core.internal.file

internal enum class PrivilegeFileScanStatFailureAction {
    SKIP_ENTRY,
    EMIT_NAME_ONLY,
    FAIL_SCAN,
}

internal fun classifyPrivilegeFileScanStatFailure(
    errno: Int,
    noEntryErrno: Int,
    accessDeniedErrno: Int,
    operationNotPermittedErrno: Int,
): PrivilegeFileScanStatFailureAction = when (errno) {
    noEntryErrno -> PrivilegeFileScanStatFailureAction.SKIP_ENTRY
    accessDeniedErrno,
    operationNotPermittedErrno,
    -> PrivilegeFileScanStatFailureAction.EMIT_NAME_ONLY
    else -> PrivilegeFileScanStatFailureAction.FAIL_SCAN
}
