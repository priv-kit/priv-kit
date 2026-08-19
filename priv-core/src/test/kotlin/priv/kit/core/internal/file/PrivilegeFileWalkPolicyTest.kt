package priv.kit.core.internal.file

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeFileWalkPolicyTest {
    @Test
    fun permissionDeniedMetadataEmitsNameOnlyWithoutFailingTheWalk() {
        assertEquals(
            PrivilegeFileWalkStatFailureAction.EMIT_NAME_ONLY,
            classifyPrivilegeFileWalkStatFailure(
                errno = EACCES,
                noEntryErrno = ENOENT,
                accessDeniedErrno = EACCES,
                operationNotPermittedErrno = EPERM,
            ),
        )
        assertEquals(
            PrivilegeFileWalkStatFailureAction.EMIT_NAME_ONLY,
            classifyPrivilegeFileWalkStatFailure(
                errno = EPERM,
                noEntryErrno = ENOENT,
                accessDeniedErrno = EACCES,
                operationNotPermittedErrno = EPERM,
            ),
        )
    }

    @Test
    fun disappearedEntriesAreSkippedAndUnexpectedErrorsFailTheWalk() {
        assertEquals(
            PrivilegeFileWalkStatFailureAction.SKIP_ENTRY,
            classifyPrivilegeFileWalkStatFailure(
                errno = ENOENT,
                noEntryErrno = ENOENT,
                accessDeniedErrno = EACCES,
                operationNotPermittedErrno = EPERM,
            ),
        )
        assertEquals(
            PrivilegeFileWalkStatFailureAction.FAIL_WALK,
            classifyPrivilegeFileWalkStatFailure(
                errno = EIO,
                noEntryErrno = ENOENT,
                accessDeniedErrno = EACCES,
                operationNotPermittedErrno = EPERM,
            ),
        )
    }

    private companion object {
        const val EPERM: Int = 1
        const val ENOENT: Int = 2
        const val EIO: Int = 5
        const val EACCES: Int = 13
    }
}
