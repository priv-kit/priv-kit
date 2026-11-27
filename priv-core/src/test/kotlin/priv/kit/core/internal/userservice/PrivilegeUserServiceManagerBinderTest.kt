package priv.kit.core.internal.userservice

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ResultReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.TestDedicatedUserServiceHost
import priv.kit.core.testing.TestEmbeddedUserServiceHost
import priv.kit.core.testing.TestProcess
import priv.kit.core.testing.TestUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import priv.kit.core.userservice.PrivilegeUserServiceTransactions
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceManagerBinderTest {
    @Test
    fun untaggedServiceIsIndependentOfTaggedInstances() {
        CountingEmbeddedService.reset()
        val manager = manager(TestEmbeddedUserServiceHost())
        val spec = PrivilegeUserServiceSpec(serviceClassName = CountingEmbeddedService::class.java.name)
        val requests = listOf(null, "default", "worker").map { tag ->
            PrivilegeUserServiceContract.requestBundle(spec.copy(tag = tag))
        }
        // An omitted tag and an explicit null must address the same instance.
        requests.first().remove(PrivilegeUserServiceContract.KEY_TAG)
        try {
            requests.forEach { assertSuccess(bind(manager, it)) }
            assertEquals(3, CountingEmbeddedService.created)
            assertNotNull(peek(manager, PrivilegeUserServiceContract.requestBundle(spec))
                .getBinder(PrivilegeUserServiceContract.KEY_SERVICE_BINDER))

            assertSuccess(stop(manager, PrivilegeUserServiceContract.requestBundle(spec)))
            assertFalse(peek(manager, requests[0]).containsKey(PrivilegeUserServiceContract.KEY_CONNECTION_ID))
            for (index in 1..2) {
                assertNotNull(peek(manager, requests[index]).getBinder(PrivilegeUserServiceContract.KEY_SERVICE_BINDER))
            }
            assertEquals(3, CountingEmbeddedService.created)
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun bindUpdatesDaemonWithoutRecreatingServiceThroughManager() {
        CountingEmbeddedService.reset()
        val manager = manager(TestEmbeddedUserServiceHost())
        val request = PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(serviceClassName = CountingEmbeddedService::class.java.name),
        )
        try {
            val original = bind(manager, request)
            val enabled = bind(manager, request, daemon = true)
            unbind(manager, requireNotNull(original.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID)))
            unbind(manager, requireNotNull(enabled.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID)))
            assertEquals(1L, CountingEmbeddedService.destroyed.count)

            val reconnected = peek(manager, request)
            val disabled = bind(manager, request)
            unbind(manager, requireNotNull(reconnected.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID)))
            assertEquals(1L, CountingEmbeddedService.destroyed.count)
            unbind(manager, requireNotNull(disabled.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID)))
            assertTrue(CountingEmbeddedService.destroyed.await(5, TimeUnit.SECONDS))
            assertEquals(1, CountingEmbeddedService.created)
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun peekReturnsMissingOrExistingConnectionWithoutCreatingOrReplacingService() {
        CountingEmbeddedService.reset()
        val manager = manager(TestEmbeddedUserServiceHost())
        val spec = PrivilegeUserServiceSpec(serviceClassName = CountingEmbeddedService::class.java.name)
        val request = PrivilegeUserServiceContract.requestBundle(spec)
        try {
            val missing = peek(manager, request)
            assertSuccess(missing)
            assertFalse(missing.containsKey(PrivilegeUserServiceContract.KEY_CONNECTION_ID))
            assertEquals(0, CountingEmbeddedService.created)

            val original = bind(manager, request)
            val mismatched = peek(manager, PrivilegeUserServiceContract.requestBundle(
                spec.copy(process = priv.kit.core.userservice.PrivilegeUserServiceProcess.ROOT),
            ))
            assertSuccess(mismatched)
            assertFalse(mismatched.containsKey(PrivilegeUserServiceContract.KEY_CONNECTION_ID))
            val existing = peek(manager, PrivilegeUserServiceContract.requestBundle(
                spec,
            ))
            assertSuccess(existing)
            assertEquals(1, CountingEmbeddedService.created)
            assertSuccess(unbind(manager, requireNotNull(original.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID))))
            assertSuccess(unbind(manager, requireNotNull(existing.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID))))
            assertTrue(CountingEmbeddedService.destroyed.await(5, TimeUnit.SECONDS))
            assertFalse(peek(manager, request).containsKey(PrivilegeUserServiceContract.KEY_CONNECTION_ID))
            assertEquals(1, CountingEmbeddedService.created)
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun cancellingDeliveredPeekReleasesOnlyItsConnection() {
        CountingEmbeddedService.reset()
        val manager = manager(TestEmbeddedUserServiceHost())
        val request = PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(serviceClassName = CountingEmbeddedService::class.java.name),
        )
        try {
            val original = bind(manager, request)
            val callback = ResultCallback()
            manager.peekUserService("cancel-peek", request, TestBinder(), null, callback)
            val response = callback.await()
            assertSuccess(response)
            assertNotNull(response.getBinder(PrivilegeUserServiceContract.KEY_SERVICE_BINDER))

            manager.cancelUserServiceOperation("cancel-peek")

            assertEquals(1L, CountingEmbeddedService.destroyed.count)
            assertSuccess(unbind(manager, requireNotNull(original.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID))))
            assertTrue(CountingEmbeddedService.destroyed.await(5, TimeUnit.SECONDS))
            assertEquals(1, CountingEmbeddedService.created)
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun cancellingQueuedOperationReleasesExecutorCapacity() {
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val startCalls = AtomicInteger(0)
        val process = object : TestUserServiceProcess() {
            override fun bind(connectionId: String): IBinder {
                if (startCalls.incrementAndGet() == 1) {
                    startEntered.countDown()
                    releaseStart.await(5, TimeUnit.SECONDS)
                }
                return super.bind(connectionId)
            }
        }
        val executor = ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(1),
        )
        val manager = manager(TestDedicatedUserServiceHost(process), executor)
        val first = ResultCallback()
        val cancelled = ResultCallback()
        val replacement = ResultCallback()
        try {
            manager.bindUserService("first", dedicatedRequest(), false, TestBinder(), null, first)
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            manager.bindUserService("cancelled", dedicatedRequest(), false, TestBinder(), null, cancelled)
            assertEquals(1, executor.queue.size)

            manager.cancelUserServiceOperation("cancelled")

            assertEquals(0, executor.queue.size)
            manager.bindUserService("replacement", dedicatedRequest(), false, TestBinder(), null, replacement)
            assertEquals(1, executor.queue.size)
            releaseStart.countDown()
            assertSuccess(first.await())
            manager.acknowledgeUserServiceOperation("first")
            assertSuccess(replacement.await())
            manager.acknowledgeUserServiceOperation("replacement")
            assertNull(cancelled.results.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            releaseStart.countDown()
            manager.destroyAll()
        }
    }

    @Test
    fun cancellationBeforeFutureAttachmentPurgesQueuedTask() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val submitEntered = CountDownLatch(1)
        val releaseSubmit = CountDownLatch(1)
        val delayNextReturn = AtomicBoolean(false)
        val executor = object : ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(2),
        ) {
            override fun execute(command: Runnable) {
                super.execute(command)
                if (delayNextReturn.compareAndSet(true, false)) {
                    submitEntered.countDown()
                    releaseSubmit.await(5, TimeUnit.SECONDS)
                }
            }
        }
        executor.execute {
            workerEntered.countDown()
            releaseWorker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
        val manager = manager(TestDedicatedUserServiceHost(TestUserServiceProcess()), executor)
        delayNextReturn.set(true)
        val launchThread = thread {
            manager.bindUserService(
                "cancel-before-attach",
                dedicatedRequest(),
                false,
                TestBinder(),
                null,
                ResultCallback(),
            )
        }
        try {
            assertTrue(submitEntered.await(5, TimeUnit.SECONDS))
            assertEquals(1, executor.queue.size)

            manager.cancelUserServiceOperation("cancel-before-attach")
            releaseSubmit.countDown()
            launchThread.join(5_000L)

            assertFalse(launchThread.isAlive)
            assertEquals(0, executor.queue.size)
        } finally {
            releaseSubmit.countDown()
            releaseWorker.countDown()
            manager.destroyAll()
        }
    }

    @Test
    fun pendingOperationQuotaIsReleasedByAcknowledgement() {
        val manager = manager(TestDedicatedUserServiceHost(TestUserServiceProcess()))
        val operationIds = mutableListOf<String>()
        try {
            var overflowResponse: Bundle? = null
            for (index in 0 until 1_000) {
                val operationId = "pending-$index"
                val callback = ResultCallback()
                manager.bindUserService(operationId, dedicatedRequest(), false, TestBinder(), null, callback)
                val response = callback.await()
                if (response.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, false)) {
                    operationIds += operationId
                } else {
                    overflowResponse = response
                    break
                }
            }
            assertTrue(operationIds.isNotEmpty())
            assertError(requireNotNull(overflowResponse))

            manager.acknowledgeUserServiceOperation(operationIds.first())
            val replacement = ResultCallback()
            manager.bindUserService("replacement", dedicatedRequest(), false, TestBinder(), null, replacement)
            assertSuccess(replacement.await())

            operationIds.drop(1).forEach(manager::acknowledgeUserServiceOperation)
            manager.acknowledgeUserServiceOperation("replacement")
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun nullClientIsRejectedForEveryOperation() {
        val manager = manager(TestDedicatedUserServiceHost(TestUserServiceProcess()))
        val callbacks = List(4) { ResultCallback() }
        try {
            manager.bindUserService("null-bind", dedicatedRequest(), false, null, null, callbacks[0])
            manager.unbindUserService("null-unbind", "missing", null, callbacks[1])
            manager.stopUserService("null-stop", dedicatedRequest(), null, callbacks[2])
            manager.peekUserService("null-peek", dedicatedRequest(), null, null, callbacks[3])

            callbacks.forEach { callback ->
                val response = callback.await()
                assertError(response)
                assertTrue(
                    response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                        .orEmpty()
                        .contains("client Binder"),
                )
            }
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun declarationExceptionMapsToErrorBundle() {
        val manager = manager(TestEmbeddedUserServiceHost())
        try {
            val response = bind(
                manager,
                PrivilegeUserServiceContract.requestBundle(
                    PrivilegeUserServiceSpec(
                        serviceClassName = "missing.UserService",
                        process = priv.kit.core.userservice.PrivilegeUserServiceProcess.EMBEDDED,
                    ),
                ),
            )

            assertError(response)
            assertTrue(
                response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                    .orEmpty()
                    .contains("missing.UserService"),
            )
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun malformedRequestMapsToDeclarationErrorBundle() {
        val manager = manager(TestEmbeddedUserServiceHost())
        try {
            val response = bind(manager, Bundle().apply {
                putString(PrivilegeUserServiceContract.KEY_SERVICE_CLASS_NAME, "")
                putString(PrivilegeUserServiceContract.KEY_TAG, "invalid")
            })

            assertError(response)
            assertTrue(
                response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                    .orEmpty()
                    .contains("serviceClassName must not be blank"),
            )
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun dedicatedChannelDeathSurvivesManagerResponse() {
        val host = TestDedicatedUserServiceHost(
            process = object : TestUserServiceProcess() {
                override fun bind(connectionId: String): IBinder {
                    throw android.os.DeadObjectException("endpoint died")
                }
            },
        )
        val manager = manager(host)
        try {
            val response = bind(manager, dedicatedRequest())
            val failure = priv.kit.core.binder.PrivilegeBinderCall.orElse(fallback = { it }) {
                PrivilegeUserServiceContract.throwFailure(response)
            }
            assertTrue(failure is priv.kit.core.binder.PrivilegeBinderCallFailure.Unavailable)
            assertEquals("endpoint died", failure.exception.message)
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun bindExceptionMapsToErrorBundle() {
        val host = TestDedicatedUserServiceHost(
            process = object : TestUserServiceProcess() {
                override fun bind(connectionId: String): IBinder {
                    throw IllegalStateException("bind exploded")
                }
            },
        )
        val manager = manager(host)
        try {
            val response = bind(manager, dedicatedRequest())

            assertError(response)
            assertTrue(
                response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                    .orEmpty()
                    .contains("bind exploded"),
            )
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun unbindIsDispatchedAndDestroysReleasedService() {
        CountingEmbeddedService.reset()
        val manager = manager(TestEmbeddedUserServiceHost())
        val request = PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(
                serviceClassName = CountingEmbeddedService::class.java.name,
                process = priv.kit.core.userservice.PrivilegeUserServiceProcess.EMBEDDED,
            ),
        )
        try {
            val response = bind(manager, request)
            val connectionId = requireNotNull(
                response.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID),
            )

            assertSuccess(unbind(manager, connectionId))

            assertTrue(CountingEmbeddedService.destroyed.await(5, TimeUnit.SECONDS))
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun stopUsesAsyncResponseAndDestroysUnboundDaemon() {
        CountingEmbeddedService.reset()
        val manager = manager(TestEmbeddedUserServiceHost())
        val request = PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(
                serviceClassName = CountingEmbeddedService::class.java.name,
                process = priv.kit.core.userservice.PrivilegeUserServiceProcess.EMBEDDED,
            ),
        )
        try {
            val connection = bind(manager, request, daemon = true)
            assertSuccess(connection)
            assertSuccess(unbind(manager, requireNotNull(connection.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID))))
            assertEquals(1L, CountingEmbeddedService.destroyed.count)

            assertSuccess(stop(manager, request))

            assertTrue(CountingEmbeddedService.destroyed.await(5, TimeUnit.SECONDS))
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun processLaunchFailureMapsToErrorBundle() {
        val host = object : TestDedicatedUserServiceHost(TestUserServiceProcess()) {
            override fun startDedicatedProcess(
                spec: PrivilegeUserServiceSpec,
                token: String,
            ): Process {
                throw IllegalStateException("host exploded")
            }
        }
        val manager = manager(host)
        try {
            val response = bind(manager, dedicatedRequest())

            assertError(response)
            assertTrue(
                response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                    .orEmpty()
                    .contains("host exploded"),
            )
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun cancellingPendingBindInterruptsWaitAndKillsProcess() {
        val waitEntered = CountDownLatch(1)
        val processKilled = CountDownLatch(1)
        val host = object : TestDedicatedUserServiceHost(TestUserServiceProcess()) {
            override fun startDedicatedProcess(
                spec: PrivilegeUserServiceSpec,
                token: String,
            ): Process = TestProcess()

            override fun awaitDedicatedProcess(
                token: String,
                handle: Process,
                timeoutMillis: Long,
            ): IPrivilegeUserServiceProcess {
                waitEntered.countDown()
                Thread.sleep(Long.MAX_VALUE)
                return process
            }

            override fun killDedicatedProcess(process: Process) {
                processKilled.countDown()
            }
        }
        val manager = manager(host)
        val callback = ResultCallback()
        try {
            manager.bindUserService(
                "cancel-pending-bind",
                dedicatedRequest(),
                false,
                TestBinder(),
                null,
                callback,
            )
            assertTrue(waitEntered.await(5, TimeUnit.SECONDS))

            manager.cancelUserServiceOperation("cancel-pending-bind")

            assertTrue(processKilled.await(5, TimeUnit.SECONDS))
            assertNull(callback.results.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun cancellingDeliveredBindDestroysUnacceptedConnection() {
        CountingEmbeddedService.reset()
        val manager = manager(TestEmbeddedUserServiceHost())
        val request = PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(
                serviceClassName = CountingEmbeddedService::class.java.name,
                process = priv.kit.core.userservice.PrivilegeUserServiceProcess.EMBEDDED,
            ),
        )
        val callback = ResultCallback()
        try {
            manager.bindUserService("cancel-bind", request, true, TestBinder(), null, callback)
            assertSuccess(callback.await())

            manager.cancelUserServiceOperation("cancel-bind")

            assertTrue(CountingEmbeddedService.destroyed.await(5, TimeUnit.SECONDS))
            assertEquals(1, CountingEmbeddedService.created)
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun clientDeathDestroysDeliveredUnacceptedConnection() {
        CountingEmbeddedService.reset()
        val manager = manager(TestEmbeddedUserServiceHost())
        val request = PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(
                serviceClassName = CountingEmbeddedService::class.java.name,
                process = priv.kit.core.userservice.PrivilegeUserServiceProcess.EMBEDDED,
            ),
        )
        val client = TestBinder()
        val receiver = ResultCallback()
        try {
            manager.bindUserService("dead-client-bind", request, true, client, null, receiver)
            assertSuccess(receiver.await())

            client.killBinder(notifyDeathRecipients = true)

            assertTrue(CountingEmbeddedService.destroyed.await(5, TimeUnit.SECONDS))
            assertEquals(1, CountingEmbeddedService.created)
        } finally {
            manager.destroyAll()
        }
    }

    private fun bind(
        manager: PrivilegeUserServiceManagerBinder,
        request: Bundle,
        daemon: Boolean = false,
    ): Bundle {
        val operationId = "bind-${System.nanoTime()}"
        val callback = ResultCallback()
        manager.bindUserService(operationId, request, daemon, TestBinder(), null, callback)
        return callback.await().also {
            manager.acknowledgeUserServiceOperation(operationId)
        }
    }

    private fun stop(
        manager: PrivilegeUserServiceManagerBinder,
        request: Bundle,
    ): Bundle {
        val operationId = "stop-${System.nanoTime()}"
        val callback = ResultCallback()
        manager.stopUserService(operationId, request, TestBinder(), callback)
        return callback.await().also {
            manager.acknowledgeUserServiceOperation(operationId)
        }
    }

    private fun peek(
        manager: PrivilegeUserServiceManagerBinder,
        request: Bundle,
    ): Bundle {
        val operationId = "peek-${System.nanoTime()}"
        val callback = ResultCallback()
        manager.peekUserService(operationId, request, TestBinder(), null, callback)
        return callback.await().also {
            manager.acknowledgeUserServiceOperation(operationId)
        }
    }

    private fun unbind(
        manager: PrivilegeUserServiceManagerBinder,
        connectionId: String,
    ): Bundle {
        val operationId = "unbind-${System.nanoTime()}"
        val callback = ResultCallback()
        manager.unbindUserService(operationId, connectionId, TestBinder(), callback)
        return callback.await().also {
            manager.acknowledgeUserServiceOperation(operationId)
        }
    }

    private fun manager(
        host: PrivilegeUserServiceHost,
        executor: ThreadPoolExecutor? = null,
    ): PrivilegeUserServiceManagerBinder {
        val registry = PrivilegeUserServiceRegistry(
            host = host,
            embeddedContextRuntimeProvider = {
                error("Context runtime is not used by this test")
            },
            dedicatedStartTimeoutMillis = 1L,
        )
        return if (executor == null) {
            PrivilegeUserServiceManagerBinder(registry)
        } else {
            PrivilegeUserServiceManagerBinder(registry, executor)
        }
    }

    private fun dedicatedRequest(): Bundle =
        PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(
                serviceClassName = "test.UserService",
                process = priv.kit.core.userservice.PrivilegeUserServiceProcess.INHERIT,
            ),
        )

    private fun assertError(response: Bundle) {
        assertFalse(response.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, true))
        assertNotNull(response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE))
    }

    private fun assertSuccess(response: Bundle) {
        assertTrue(response.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, false))
    }

    private class ResultCallback : ResultReceiver(null) {
        val results = LinkedBlockingQueue<Bundle>()

        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            results.offer(resultData ?: error("UserService result data is missing"))
        }

        fun await(): Bundle =
            results.poll(5, TimeUnit.SECONDS)
                ?: error("Timed out waiting for UserService callback")
    }

    class CountingEmbeddedService : TestBinder() {
        init {
            created += 1
        }

        override fun transact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code == PrivilegeUserServiceTransactions.DESTROY_TRANSACTION_CODE) {
                destroyed.countDown()
            }
            return true
        }

        companion object {
            var created: Int = 0
            var destroyed = CountDownLatch(1)

            fun reset() {
                created = 0
                destroyed = CountDownLatch(1)
            }
        }
    }
}
