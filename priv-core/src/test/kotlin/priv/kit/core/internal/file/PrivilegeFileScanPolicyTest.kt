package priv.kit.core.internal.file

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeFileScanPolicyTest {
    @Test
    fun permissionDeniedMetadataEmitsNameOnlyWithoutFailingTheScan() {
        assertEquals(
            PrivilegeFileScanStatFailureAction.EMIT_NAME_ONLY,
            classifyPrivilegeFileScanStatFailure(
                errno = EACCES,
                noEntryErrno = ENOENT,
                accessDeniedErrno = EACCES,
                operationNotPermittedErrno = EPERM,
            ),
        )
        assertEquals(
            PrivilegeFileScanStatFailureAction.EMIT_NAME_ONLY,
            classifyPrivilegeFileScanStatFailure(
                errno = EPERM,
                noEntryErrno = ENOENT,
                accessDeniedErrno = EACCES,
                operationNotPermittedErrno = EPERM,
            ),
        )
    }

    @Test
    fun disappearedEntriesAreSkippedAndUnexpectedErrorsFailTheScan() {
        assertEquals(
            PrivilegeFileScanStatFailureAction.SKIP_ENTRY,
            classifyPrivilegeFileScanStatFailure(
                errno = ENOENT,
                noEntryErrno = ENOENT,
                accessDeniedErrno = EACCES,
                operationNotPermittedErrno = EPERM,
            ),
        )
        assertEquals(
            PrivilegeFileScanStatFailureAction.FAIL_SCAN,
            classifyPrivilegeFileScanStatFailure(
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
