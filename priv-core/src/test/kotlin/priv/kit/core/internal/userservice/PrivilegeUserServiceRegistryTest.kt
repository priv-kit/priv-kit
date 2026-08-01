package priv.kit.core.internal.userservice

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.TestDedicatedUserServiceHost
import priv.kit.core.testing.TestEmbeddedUserServiceHost
import priv.kit.core.testing.TestProcess
import priv.kit.core.testing.TestUserServiceProcess
import priv.kit.core.userservice.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PrivilegeUserServiceRegistryTest {
    @Test
    fun specDefaultsToDedicatedProcess() {
        val spec = PrivilegeUserServiceSpec(serviceClassName = EmbeddedService::class.java.name)

        assertEquals(false, spec.embedded)
        assertEquals(false, spec.daemon)
    }

    @Test
    fun nonDaemonServiceIsDestroyedWhenOwnerDies() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val owner = TestBinder()
        val spec = embeddedSpec(
            tag = PrivilegeUserServiceSpec.DEFAULT_TAG,
            version = 1,
            daemon = false,
        )

        registry.start(spec, owner)
        owner.killBinder(notifyDeathRecipients = true)
        val result = registry.bind(spec, TestBinder())

        assertEquals(2, EmbeddedService.created)
        registry.unbind(result.connectionId)
    }

    @Test
    fun daemonServiceSurvivesOwnerDeath() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val owner = TestBinder()
        val spec = embeddedSpec(
            tag = PrivilegeUserServiceSpec.DEFAULT_TAG,
            version = 1,
            daemon = true,
        )

        registry.start(spec, owner)
        owner.killBinder(notifyDeathRecipients = true)
        val result = registry.bind(spec, TestBinder())

        assertEquals(1, EmbeddedService.created)
        registry.unbind(result.connectionId)
        registry.stop(spec)
    }

    @Test
    fun daemonBindKeepsServiceAfterUnbindUntilStop() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec(
            tag = PrivilegeUserServiceSpec.DEFAULT_TAG,
            version = 1,
            daemon = true,
        )

        val first = registry.bind(spec, TestBinder())
        registry.unbind(first.connectionId)
        val second = registry.bind(spec, TestBinder())

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
        val host = TestDedicatedUserServiceHost(firstProcess)
        val registry = registry(host)
        val spec = dedicatedSpec()

        val first = registry.bind(spec, TestBinder())
        firstProcess.killBinder()
        host.process = TestUserServiceProcess()
        val result = registry.bind(spec, TestBinder())

        assertNotSame(first.binder, result.binder)
    }

    @Test
    fun embeddedModeCreatesSeparateInstancesForDifferentTags() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val client = TestBinder()

        val first = registry.bind(
            embeddedSpec(tag = "first", version = 1, daemon = false),
            client,
        )
        val second = registry.bind(
            embeddedSpec(tag = "second", version = 1, daemon = false),
            client,
        )

        assertEquals(2, EmbeddedService.created)
        assertNotSame(first.binder, second.binder)

        registry.unbind(first.connectionId)
        registry.unbind(second.connectionId)
    }

    @Test
    fun embeddedVersionChangeDestroysPreviousInstance() {
        EmbeddedService.reset()
        val registry = registry(TestEmbeddedUserServiceHost())
        val client = TestBinder()

        val first = registry.bind(
            embeddedSpec(
                tag = PrivilegeUserServiceSpec.DEFAULT_TAG,
                version = 1,
                daemon = false,
            ),
            client,
        )
        val second = registry.bind(
            embeddedSpec(
                tag = PrivilegeUserServiceSpec.DEFAULT_TAG,
                version = 2,
                daemon = false,
            ),
            client,
        )

        assertEquals(2, EmbeddedService.created)
        assertNotSame(first.binder, second.binder)
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
                timeoutMillis: Long,
            ): IPrivilegeUserServiceProcess {
                waitEntered.countDown()
                check(releaseWait.await(5, TimeUnit.SECONDS))
                return process
            }
        }
        val registry = registry(host)
        val dedicatedWorker = thread(name = "dedicated-registry-start") {
            registry.start(dedicatedSpec(), TestBinder())
        }
        try {
            assertEquals(true, waitEntered.await(5, TimeUnit.SECONDS))

            val embedded = registry.bind(
                embeddedSpec(
                    tag = "parallel-embedded",
                    version = 1,
                    daemon = false,
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
        val spec = embeddedSpec(
            tag = PrivilegeUserServiceSpec.DEFAULT_TAG,
            version = 1,
            daemon = true,
        )

        val accepted = registry.bind(spec, TestBinder())
        accepted.accept()
        val cancelled = registry.bind(spec, TestBinder())
        cancelled.cancel()
        registry.unbind(accepted.connectionId)
        val rebound = registry.bind(spec, TestBinder())
        rebound.accept()

        assertEquals(1, EmbeddedService.created)
        registry.unbind(rebound.connectionId)
        registry.stop(spec)
    }

    @Test
    fun cancellingOverlappingStartsOutOfOrderDoesNotLeakService() {
        val destroyed = CountDownLatch(1)
        val process = object : TestUserServiceProcess() {
            override fun destroy() {
                destroyed.countDown()
            }
        }
        val registry = registry(TestDedicatedUserServiceHost(process))
        val spec = dedicatedSpec()

        val first = registry.start(spec, TestBinder())
        val second = registry.start(spec, TestBinder())
        first.rollback()
        second.rollback()

        assertEquals(true, destroyed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun acceptedOverlappingStartSurvivesOtherCancellation() {
        val destroyed = CountDownLatch(1)
        val process = object : TestUserServiceProcess() {
            override fun destroy() {
                destroyed.countDown()
            }
        }
        val registry = registry(TestDedicatedUserServiceHost(process))
        val spec = dedicatedSpec()

        val first = registry.start(spec, TestBinder())
        val second = registry.start(spec, TestBinder())
        first.rollback()
        second.accept()

        assertEquals(false, destroyed.await(100, TimeUnit.MILLISECONDS))
        registry.stop(spec)
        assertEquals(true, destroyed.await(5, TimeUnit.SECONDS))
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

        assertThrows(PrivilegeUserServiceException::class.java) {
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
                embeddedSpec(
                    tag = PrivilegeUserServiceSpec.DEFAULT_TAG,
                    version = 1,
                    daemon = false,
                ),
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

        assertThrows(PrivilegeUserServiceException::class.java) {
            registry.bind(
                PrivilegeUserServiceSpec(
                    serviceClassName = "missing.Service",
                    embedded = true,
                ),
                TestBinder(),
            )
        }
    }

    @Test
    fun contextConstructorRequiresContextConfig() {
        assertThrows(PrivilegeUserServiceException::class.java) {
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
        assertThrows(PrivilegeUserServiceException::class.java) {
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
        tag: String,
        version: Int,
        daemon: Boolean,
    ): PrivilegeUserServiceSpec =
        PrivilegeUserServiceSpec(
            serviceClassName = EmbeddedService::class.java.name,
            tag = tag,
            version = version,
            embedded = true,
            daemon = daemon,
        )

    private fun dedicatedSpec(): PrivilegeUserServiceSpec =
        PrivilegeUserServiceSpec(
            serviceClassName = EmbeddedService::class.java.name,
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
