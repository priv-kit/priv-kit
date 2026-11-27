package priv.kit.core.internal.userservice

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.TestDedicatedUserServiceHost
import priv.kit.core.testing.TestEmbeddedUserServiceHost
import priv.kit.core.testing.TestProcess
import priv.kit.core.testing.TestUserServiceProcess
import priv.kit.core.userservice.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceRegistryTest {
    @Test
    fun peekMissingServiceDoesNotCreateInstanceOrProcess() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        try {
            assertNull(registry.peek(embeddedSpec("missing"), TestBinder()))
            assertNull(registry.peek(dedicatedSpec(), TestBinder()))
            assertNull(registry.peek(PrivilegeUserServiceSpec(), TestBinder()))
            assertEquals(0, EmbeddedService.created)
        } finally {
            registry.destroyAll()
        }
    }

    @Test
    fun peekReusesMatchingInstanceWithoutChangingDaemon() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec("existing")
        try {
            val original = registry.bind(spec, TestBinder())
            assertNull(registry.peek(spec.copy(tag = "other"), TestBinder()))
            assertNull(registry.peek(spec.copy(serviceClassName = "other.Service"), TestBinder()))
            val connection = requireNotNull(registry.peek(
                spec,
                TestBinder(),
            ))
            connection.accept()
            registry.unbind(original.connectionId)
            val rebound = registry.bind(spec, TestBinder())
            assertEquals(1, EmbeddedService.created)
            registry.unbind(rebound.connectionId)
            registry.unbind(connection.connectionId)
            assertNull(registry.peek(spec, TestBinder()))
        } finally {
            registry.destroyAll()
        }
    }

    @Test
    fun peekRequiresMatchingProcessWithoutReplacingInstance() {
        for (actualProcess in PrivilegeUserServiceProcess.entries) {
            EmbeddedService.reset()
            var starts = 0
            val host = object : TestDedicatedUserServiceHost() {
                override fun startDedicatedProcess(spec: PrivilegeUserServiceSpec, token: String): Process {
                    starts += 1
                    return TestProcess()
                }
            }
            val registry = registry(host)
            val spec = PrivilegeUserServiceSpec(
                serviceClassName = EmbeddedService::class.java.name,
                process = actualProcess,
            )
            try {
                registry.bind(spec, TestBinder())
                for (requestedProcess in PrivilegeUserServiceProcess.entries) {
                    val connection = registry.peek(spec.copy(process = requestedProcess), TestBinder())
                    if (requestedProcess == actualProcess) {
                        requireNotNull(connection).accept()
                        registry.unbind(connection.connectionId)
                    } else {
                        assertNull("$actualProcess must not match $requestedProcess", connection)
                    }
                }
                val existing = requireNotNull(registry.peek(spec, TestBinder()))
                registry.unbind(existing.connectionId)
                assertEquals(if (actualProcess.isEmbedded) 1 else 0, EmbeddedService.created)
                assertEquals(if (actualProcess.isEmbedded) 0 else 1, starts)
            } finally {
                registry.destroyAll()
            }
        }
    }

    @Test
    fun peekReconnectsDedicatedDaemonAfterClientDeathWithoutStartingAnotherProcess() {
        val process = TestUserServiceProcess()
        var starts = 0
        val host = object : TestDedicatedUserServiceHost(process) {
            override fun startDedicatedProcess(spec: PrivilegeUserServiceSpec, token: String): Process {
                starts += 1
                return TestProcess()
            }
        }
        val registry = registry(host)
        val spec = dedicatedSpec()
        try {
            val oldClient = TestBinder()
            val original = registry.bind(spec, oldClient, daemon = true)
            original.accept()
            oldClient.killBinder(notifyDeathRecipients = true)
            registry.destroyOnOwnerDeath()

            val connection = requireNotNull(registry.peek(spec, TestBinder()))
            connection.accept()
            assertEquals(1, starts)
            registry.unbind(connection.connectionId)

            process.killBinder()
            assertNull(registry.peek(spec, TestBinder()))
            assertEquals(1, starts)
        } finally {
            registry.destroyAll()
        }
    }

    @Test
    fun peekDoesNotReactivateStoppedDaemon() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec("stopped")
        try {
            val original = registry.bind(spec, TestBinder(), daemon = true)
            original.accept()
            registry.stop(spec)
            registry.unbind(original.connectionId)

            assertNull(registry.peek(spec, TestBinder()))
            assertEquals(1, EmbeddedService.created)
        } finally {
            registry.destroyAll()
        }
    }

    @Test
    fun specDefaultsToEmbeddedProcess() {
        EmbeddedService.reset()
        val spec = PrivilegeUserServiceSpec(serviceClassName = EmbeddedService::class.java.name)

        assertEquals(PrivilegeUserServiceProcess.EMBEDDED, spec.process)
        val registry = registry(TestEmbeddedUserServiceHost())
        val connection = registry.bind(spec, TestBinder())
        assertEquals(1, EmbeddedService.created)
        registry.unbind(connection.connectionId)
    }

    @Test
    fun nonDaemonServiceIsDestroyedWhenOwnerDies() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val owner = TestBinder()
        val spec = embeddedSpec()

        registry.bind(spec, owner).accept()
        owner.killBinder(notifyDeathRecipients = true)
        val result = registry.bind(spec, TestBinder())

        assertEquals(2, EmbeddedService.created)
        registry.unbind(result.connectionId)
    }

    @Test
    fun daemonServiceSurvivesOwnerDeathAndReleasesOldConnections() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val owner = TestBinder()
        val spec = embeddedSpec("owner-death")
        val original = registry.bind(spec, owner, daemon = true)
        original.accept()

        owner.killBinder(notifyDeathRecipients = true)
        registry.destroyOnOwnerDeath()
        val reconnected = requireNotNull(registry.peek(spec, TestBinder()))
        assertEquals(1, EmbeddedService.created)
        registry.unbind(reconnected.connectionId)
        registry.stop(spec)
        assertNull(registry.peek(spec, TestBinder()))
    }

    @Test
    fun daemonBindKeepsServiceAfterUnbindUntilStop() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec()

        val first = registry.bind(spec, TestBinder(), daemon = true).also { it.accept() }
        registry.unbind(first.connectionId)
        val second = registry.bind(spec, TestBinder(), daemon = true).also { it.accept() }

        assertEquals(1, EmbeddedService.created)
        registry.unbind(second.connectionId)
        registry.stop(spec)
    }

    @Test
    fun dedicatedProcessDeathMakesLateUnbindIdempotent() {
        val process = TestUserServiceProcess()
        val registry = registry(TestDedicatedUserServiceHost(process))
        val spec = dedicatedSpec()

        val result = registry.bind(spec, TestBinder())
        process.killBinder()

        registry.unbind(result.connectionId)
        registry.unbind(result.connectionId)
    }

    @Test
    fun dedicatedProcessDeathAllowsNextBindToCreateReplacement() {
        val firstProcess = TestUserServiceProcess()
        var starts = 0
        val host = object : TestDedicatedUserServiceHost(firstProcess) {
            override fun startDedicatedProcess(spec: PrivilegeUserServiceSpec, token: String): Process {
                starts += 1
                return TestProcess()
            }
        }
        val registry = registry(host)
        val spec = dedicatedSpec()

        registry.bind(spec, TestBinder())
        firstProcess.killBinder()
        host.process = TestUserServiceProcess()
        val result = registry.bind(spec, TestBinder())

        assertEquals(2, starts)
        registry.unbind(result.connectionId)
    }

    @Test
    fun embeddedModeCreatesSeparateInstancesForDifferentTags() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val client = TestBinder()

        val first = registry.bind(
            embeddedSpec(tag = "first"),
            client,
        )
        val second = registry.bind(
            embeddedSpec(tag = "second"),
            client,
        )

        assertEquals(2, EmbeddedService.created)

        registry.unbind(first.connectionId)
        registry.unbind(second.connectionId)
    }

    @Test
    fun embeddedInstanceCanBeRecreatedAfterUnbindAndStop() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val client = TestBinder()
        val spec = embeddedSpec()
        try {
            val first = registry.bind(spec, client, daemon = true)
            first.accept()
            registry.unbind(first.connectionId)
            registry.stop(spec)
            assertNull(registry.peek(spec, client))
            registry.bind(spec, client, daemon = true)

            assertEquals(2, EmbeddedService.created)
        } finally {
            registry.destroyAll()
        }
    }

    @Test
    fun dedicatedStartupDoesNotHoldGlobalRegistryLock() {
        EmbeddedService.reset()
        val waitEntered = CountDownLatch(1)
        val releaseWait = CountDownLatch(1)
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
                check(releaseWait.await(5, TimeUnit.SECONDS))
                return process
            }
        }
        val registry = registry(host)
        val dedicatedWorker = thread(name = "dedicated-registry-start") {
            registry.bind(dedicatedSpec(), TestBinder()).cancel()
        }
        try {
            assertEquals(true, waitEntered.await(5, TimeUnit.SECONDS))

            val embedded = registry.bind(
                embeddedSpec(
                    tag = "parallel-embedded",
                ),
                TestBinder(),
            )

            assertEquals(1, EmbeddedService.created)
            registry.unbind(embedded.connectionId)
        } finally {
            releaseWait.countDown()
            dedicatedWorker.join(5_000)
        }
        assertEquals(false, dedicatedWorker.isAlive)
        registry.stop(dedicatedSpec())
    }

    @Test
    fun cancelledDaemonBindRestoresPreviousKeepAliveState() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec()

        val accepted = registry.bind(spec, TestBinder(), daemon = true)
        accepted.accept()
        val cancelled = registry.bind(spec, TestBinder(), daemon = true)
        cancelled.cancel()
        registry.unbind(accepted.connectionId)
        val rebound = registry.bind(spec, TestBinder(), daemon = true)
        rebound.accept()

        assertEquals(1, EmbeddedService.created)
        registry.unbind(rebound.connectionId)
        registry.stop(spec)
    }

    @Test
    fun cancellingOverlappingDaemonBindsDoesNotLeakService() {
        val destroyed = CountDownLatch(1)
        val process = object : TestUserServiceProcess() {
            override fun destroy() { destroyed.countDown() }
        }
        val registry = registry(TestDedicatedUserServiceHost(process))
        val spec = dedicatedSpec()
        val first = registry.bind(spec, TestBinder(), daemon = true)
        val second = registry.bind(spec, TestBinder(), daemon = true)
        first.cancel()
        second.cancel()

        assertEquals(true, destroyed.await(5, TimeUnit.SECONDS))
        assertNull(registry.peek(spec, TestBinder()))
    }

    @Test
    fun acceptedDaemonBindSurvivesOtherCancellation() {
        val destroyed = CountDownLatch(1)
        val process = object : TestUserServiceProcess() {
            override fun destroy() { destroyed.countDown() }
        }
        val registry = registry(TestDedicatedUserServiceHost(process))
        val spec = dedicatedSpec()
        val first = registry.bind(spec, TestBinder(), daemon = true)
        val second = registry.bind(spec, TestBinder(), daemon = true)
        second.accept()
        first.cancel()
        registry.unbind(second.connectionId)

        assertEquals(1L, destroyed.count)
        registry.stop(spec)
        assertEquals(true, destroyed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun daemonCanBeEnabledAndDisabledWithoutReplacingAnyProcessMode() {
        for (mode in PrivilegeUserServiceProcess.entries) {
            EmbeddedService.reset()
            var launches = 0
            val host = object : TestDedicatedUserServiceHost() {
                override fun startDedicatedProcess(spec: PrivilegeUserServiceSpec, token: String): Process {
                    launches += 1
                    return TestProcess()
                }
            }
            val registry = registry(host)
            val spec = embeddedSpec("dynamic").copy(process = mode)
            try {
                val original = registry.bind(spec, TestBinder()).also { it.accept() }
                val enabled = registry.bind(spec, TestBinder(), daemon = true).also { it.accept() }
                assertEquals(true, original.binder.isBinderAlive)
                registry.unbind(original.connectionId)
                registry.unbind(enabled.connectionId)
                val peeked = requireNotNull(registry.peek(spec, TestBinder()))
                peeked.accept()
                registry.unbind(peeked.connectionId)
                val disabled = registry.bind(spec, TestBinder()).also { it.accept() }
                assertEquals(if (mode.isEmbedded) 1 else 0, EmbeddedService.created)
                assertEquals(if (mode.isEmbedded) 0 else 1, launches)
                registry.unbind(disabled.connectionId)
                assertNull(registry.peek(spec, TestBinder()))
            } finally {
                registry.destroyAll()
            }
        }
    }

    @Test
    fun cancelledPolicyChangesPreservePreviousDaemonValue() {
        for (initialDaemon in listOf(false, true)) {
            val registry = registry(TestEmbeddedUserServiceHost())
            val spec = embeddedSpec("cancel-change")
            try {
                val original = registry.bind(spec, TestBinder(), initialDaemon).also { it.accept() }
                val cancelled = registry.bind(spec, TestBinder(), !initialDaemon)
                cancelled.cancel()
                registry.unbind(original.connectionId)
                val remaining = registry.peek(spec, TestBinder())
                assertEquals(initialDaemon, remaining != null)
                remaining?.let { registry.unbind(it.connectionId) }
            } finally {
                registry.destroyAll()
            }
        }
    }

    @Test
    fun stopClearsPendingBindingsAndLateAcknowledgementDoesNotAffectReplacement() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec("stop-pending")
        val original = registry.bind(spec, TestBinder(), daemon = true).also { it.accept() }
        val pending = registry.bind(spec, TestBinder(), daemon = true)
        registry.stop(spec)
        assertNull(registry.peek(spec, TestBinder()))
        val replacement = registry.bind(spec, TestBinder()).also { it.accept() }
        pending.accept()
        registry.unbind(original.connectionId)
        registry.unbind(pending.connectionId)
        assertEquals(2, EmbeddedService.created)
        registry.unbind(replacement.connectionId)
        assertNull(registry.peek(spec, TestBinder()))
    }

    @Test
    fun lateAcknowledgementDoesNotOverrideNewerAcceptedDaemonPolicy() {
        val registry = registry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec("out-of-order")
        val first = registry.bind(spec, TestBinder(), daemon = true)
        val second = registry.bind(spec, TestBinder(), daemon = false)
        second.accept()
        first.accept()
        registry.unbind(first.connectionId)
        registry.unbind(second.connectionId)
        assertNull(registry.peek(spec, TestBinder()))
    }

    @Test
    fun cancelledNewerBindDoesNotDiscardEarlierAcceptedPolicy() {
        val registry = registry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec("cancel-newer")
        val first = registry.bind(spec, TestBinder(), daemon = true)
        val second = registry.bind(spec, TestBinder(), daemon = false)
        first.accept()
        second.cancel()
        registry.unbind(first.connectionId)
        val remaining = requireNotNull(registry.peek(spec, TestBinder()))
        registry.unbind(remaining.connectionId)
        registry.stop(spec)
        assertNull(registry.peek(spec, TestBinder()))
    }

    @Test
    fun disablingDaemonMakesServiceFollowOwnerDeath() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec("disable-owner")
        val owner = TestBinder()
        registry.bind(spec, owner, daemon = true).accept()
        registry.bind(spec, owner, daemon = false).accept()
        assertEquals(1, EmbeddedService.created)
        owner.killBinder(notifyDeathRecipients = true)
        registry.destroyOnOwnerDeath()
        assertNull(registry.peek(spec, TestBinder()))
    }

    @Test
    fun bindFailureAfterRemoteBindDestroysUnusedProcess() {
        val destroyed = CountDownLatch(1)
        val process = object : TestUserServiceProcess() {
            override fun destroy() {
                destroyed.countDown()
            }
        }
        val registry = registry(TestDedicatedUserServiceHost(process))
        val deadClient = TestBinder().apply {
            killBinder(notifyDeathRecipients = false)
        }

        assertThrows(priv.kit.core.binder.PrivilegeUnavailableException::class.java) {
            registry.bind(dedicatedSpec(), deadClient)
        }

        assertEquals(true, destroyed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun destroyTransactionCodeMatchesAidlReservedCode() {
        assertEquals(16777114, PrivilegeUserServiceTransactions.DESTROY_AIDL_CODE)
        assertEquals(16777115, PrivilegeUserServiceTransactions.DESTROY_TRANSACTION_CODE)
    }

    @Test
    fun embeddedModeFallsBackWhenContextClassLoaderCannotFindService() {
        EmbeddedService.reset()
        val originalClassLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = object : ClassLoader(null) {}
        try {
            val registry = registry(TestEmbeddedUserServiceHost())
            val client = TestBinder()

            val result = registry.bind(
                embeddedSpec(),
                client,
            )

            assertEquals(1, EmbeddedService.created)
            assertEquals(true, result.connectionId.isNotBlank())
        } finally {
            Thread.currentThread().contextClassLoader = originalClassLoader
        }
    }

    @Test
    fun missingEmbeddedClassThrowsDeclarationException() {
        val registry = registry(TestEmbeddedUserServiceHost())

        assertThrows(IllegalStateException::class.java) {
            registry.bind(
                PrivilegeUserServiceSpec(
                    serviceClassName = "missing.Service",
                    process = priv.kit.core.userservice.PrivilegeUserServiceProcess.EMBEDDED,
                ),
                TestBinder(),
            )
        }
    }

    @Test
    fun contextConstructorRequiresContextConfig() {
        assertThrows(IllegalStateException::class.java) {
            PrivilegeUserServiceLoader.instantiate(
                serviceClassName = ContextOnlyService::class.java.name,
                contextConfig = null,
            )
        }
    }

    @Test
    fun embeddedContextCreationFailureFallsBackToNoArgWhenAvailable() {
        val instance = PrivilegeUserServiceLoader.instantiate(
            serviceClassName = BothConstructorsService::class.java.name,
            contextConfig = PrivilegeUserServiceLoader.ContextConfig(
                packageName = "priv.kit.test",
                userId = 0,
                mode = PrivilegeUserServiceLoader.ContextMode.PACKAGE_CONTEXT_ONLY,
                contextRuntimeProvider = { error("Context unavailable") },
            ),
        ) as BothConstructorsService

        assertEquals("no-arg", instance.createdWith)
    }

    @Test
    fun dedicatedContextCreationFailureDoesNotFallbackToNoArg() {
        assertThrows(IllegalStateException::class.java) {
            PrivilegeUserServiceLoader.instantiate(
                serviceClassName = BothConstructorsService::class.java.name,
                contextConfig = PrivilegeUserServiceLoader.ContextConfig(
                    packageName = "priv.kit.test",
                    userId = 0,
                    mode = PrivilegeUserServiceLoader.ContextMode.APPLICATION_WITH_PACKAGE_FALLBACK,
                    contextRuntimeProvider = { error("Context unavailable") },
                ),
            )
        }
    }

    private fun embeddedSpec(
        tag: String? = null,
    ): PrivilegeUserServiceSpec =
        PrivilegeUserServiceSpec(
            serviceClassName = EmbeddedService::class.java.name,
            tag = tag,
            process = priv.kit.core.userservice.PrivilegeUserServiceProcess.EMBEDDED,
        )

    private fun dedicatedSpec(): PrivilegeUserServiceSpec =
        PrivilegeUserServiceSpec(
            serviceClassName = EmbeddedService::class.java.name,
            process = PrivilegeUserServiceProcess.INHERIT,
        )

    private fun registry(host: PrivilegeUserServiceHost): PrivilegeUserServiceRegistry =
        PrivilegeUserServiceRegistry(
            host = host,
            embeddedContextRuntimeProvider = {
                error("Context runtime is not used by this test")
            },
        )

    class EmbeddedService :
        TestBinder() {
        init {
            created += 1
        }

        companion object {
            var created = 0

            fun reset() {
                created = 0
            }
        }
    }

    class ContextOnlyService(
        @Suppress("UNUSED_PARAMETER")
        context: Context,
    ) : TestBinder()

    class BothConstructorsService private constructor(
        val createdWith: String,
    ) : TestBinder() {
        constructor() : this("no-arg")

        constructor(context: Context) : this(context.packageName)
    }

}
