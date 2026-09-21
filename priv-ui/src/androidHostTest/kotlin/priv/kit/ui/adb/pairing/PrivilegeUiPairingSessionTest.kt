package priv.kit.ui.adb.pairing

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUiPairingSessionTest {
    @Test
    fun replacingOperationRejectsOldCallbacksAndReusesOnePermit() {
        var acquired = 0
        var released = 0
        val session = PrivilegeUiPairingSession {
            acquired++
            AutoCloseable { released++ }
        }
        assertTrue(session.acquire())
        val oldId = session.begin("device")
        val oldJob = Job()
        session.attach(oldId, oldJob)
        session.discovered(12345, "device")
        assertTrue(session.acquire())
        val nextId = session.nextOperation()
        assertTrue(oldJob.isCancelled)
        assertFalse(session.isCurrent(oldId))
        assertTrue(session.isCurrent(nextId))
        assertEquals(12345, session.port)
        val staleJob = Job()
        session.attach(oldId, staleJob)
        assertTrue(staleJob.isCancelled)
        session.close()
        session.close()
        assertNull(session.port)
        assertNull(session.deviceName)
        assertEquals(1, acquired)
        assertEquals(1, released)
    }

    @Test
    fun completionKeepsCoroutineAliveAndPermitUntilPresentationIsPublished() {
        var released = 0
        val session = PrivilegeUiPairingSession { AutoCloseable { released++ } }
        session.acquire()
        val id = session.begin(null)
        val job = Job()
        session.attach(id, job)
        session.discovered(12345, null)
        session.finishOperation()
        assertFalse(session.isCurrent(id))
        assertTrue(job.isActive)
        assertNull(session.port)
        assertEquals(0, released)
        session.release()
        session.close()
        assertEquals(1, released)
        job.cancel()
    }
}
