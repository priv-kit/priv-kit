package priv.kit.core.internal.server

import android.database.ContentObserver
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeOwnerProcessObserverTest {
    @Test
    fun ownerStartNotificationsWakeSignalAndCoalesceBeforeWait() {
        val uri = Uri.parse("content://example.app.privilege.handshake/owner-started")
        val registrar = RecordingRegistrar()
        val signal = PrivilegeOwnerProcessSignal()
        val observer = PrivilegeOwnerProcessObserver(
            ownerProcessStartedUri = uri,
            registrar = registrar,
            onOwnerProcessStarted = signal::signal,
        )

        assertTrue(observer.register())
        assertEquals(uri, registrar.registeredUri)

        registrar.observer!!.onChange(false, uri)
        registrar.observer!!.onChange(false, uri)

        assertEquals(2L, signal.awaitNext(afterSequence = 0L, timeoutMillis = 0L))
    }

    @Test
    fun registrationFailureReturnsFalseAndSuppressesCallbacks() {
        val signal = PrivilegeOwnerProcessSignal()
        val registrar = RecordingRegistrar(registerFailure = SecurityException("denied"))
        val observer = PrivilegeOwnerProcessObserver(
            ownerProcessStartedUri = Uri.parse("content://example.app/owner-started"),
            registrar = registrar,
            onOwnerProcessStarted = signal::signal,
        )

        assertFalse(observer.register())
        registrar.observer!!.onChange(false)
        observer.close()

        assertEquals(0L, signal.snapshot())
        assertEquals(0, registrar.unregisterCount)
    }

    @Test
    fun closeUnregistersExactlyOnceAndIgnoresLateCallbacks() {
        val signal = PrivilegeOwnerProcessSignal()
        val registrar = RecordingRegistrar()
        val observer = PrivilegeOwnerProcessObserver(
            ownerProcessStartedUri = Uri.parse("content://example.app/owner-started"),
            registrar = registrar,
            onOwnerProcessStarted = signal::signal,
        )
        assertTrue(observer.register())
        val registeredObserver = registrar.observer!!

        observer.close()
        observer.close()
        registeredObserver.onChange(false)

        assertEquals(1, registrar.unregisterCount)
        assertEquals(0L, signal.snapshot())
    }

    @Test
    fun notificationFromLinkedLiveOwnerIsAcknowledged() {
        val signal = PrivilegeOwnerProcessSignal()
        val notificationSequence = signal.signal(ownerIsAlive = true)

        assertEquals(notificationSequence, signal.acknowledgedSnapshot())
        assertNull(
            signal.awaitNext(
                afterSequence = signal.acknowledgedSnapshot(),
                timeoutMillis = 0L,
            ),
        )
    }

    @Test
    fun notificationBeforeOwnerDeathRemainsPendingWhenNotAcknowledged() {
        val signal = PrivilegeOwnerProcessSignal()
        signal.signal(ownerIsAlive = true)
        val baseline = signal.acknowledgedSnapshot()
        val restartNotification = signal.signal(ownerIsAlive = false)

        assertEquals(restartNotification, signal.awaitNext(baseline, timeoutMillis = 0L))
    }

    @Test
    fun successfulOwnerLinkAcknowledgesNotificationThatArrivedDuringLinkSetup() {
        val signal = PrivilegeOwnerProcessSignal()
        val sequenceBeforeLink = signal.snapshot()
        val notificationDuringLink = signal.signal(ownerIsAlive = false)

        val linkedSequence = signal.acknowledgeCurrent()

        assertTrue(notificationDuringLink > sequenceBeforeLink)
        assertEquals(notificationDuringLink, linkedSequence)
        assertEquals(linkedSequence, signal.acknowledgedSnapshot())
        assertNull(signal.awaitNext(linkedSequence, timeoutMillis = 0L))
    }

    @Test
    fun waitingWorkerWakesForNextGeneration() {
        val signal = PrivilegeOwnerProcessSignal()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val nextSequence = executor.submit<Long?> {
                signal.awaitNext(afterSequence = 0L, timeoutMillis = 5_000L)
            }

            signal.signal()

            assertEquals(1L, requireNotNull(nextSequence.get(1L, TimeUnit.SECONDS)))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun waitReturnsNullWhenNoNewGenerationArrives() {
        val signal = PrivilegeOwnerProcessSignal()

        assertNull(signal.awaitNext(afterSequence = 0L, timeoutMillis = 1L))
    }

    private class RecordingRegistrar(
        private val registerFailure: RuntimeException? = null,
    ) : PrivilegeOwnerProcessObserver.Registrar {
        var registeredUri: Uri? = null
        var observer: ContentObserver? = null
        var unregisterCount = 0

        override fun register(
            uri: Uri,
            observer: ContentObserver,
        ) {
            registeredUri = uri
            this.observer = observer
            registerFailure?.let { throw it }
        }

        override fun unregister(observer: ContentObserver) {
            assertEquals(this.observer, observer)
            unregisterCount += 1
        }
    }
}
