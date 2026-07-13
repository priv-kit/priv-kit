package priv.kit.internal.userservice

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.testing.TestBinder
import priv.kit.testing.TestDedicatedUserServiceHost
import priv.kit.testing.TestEmbeddedUserServiceHost
import priv.kit.testing.TestUserServiceProcess
import priv.kit.userservice.*

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
        val registry = PrivilegeUserServiceRegistry(TestEmbeddedUserServiceHost())
        val owner = TestBinder()
        val spec = embeddedSpec()

        registry.start(spec, owner)
        owner.killBinder()
        val result = registry.bind(spec, TestBinder())

        assertEquals(2, EmbeddedService.created)
        registry.unbind(result.connectionId)
    }

    @Test
    fun daemonServiceSurvivesOwnerDeath() {
        EmbeddedService.reset()
        val registry = PrivilegeUserServiceRegistry(TestEmbeddedUserServiceHost())
        val owner = TestBinder()
        val spec = embeddedSpec(daemon = true)

        registry.start(spec, owner)
        owner.killBinder()
        val result = registry.bind(spec, TestBinder())

        assertEquals(1, EmbeddedService.created)
        registry.unbind(result.connectionId)
        registry.stop(spec)
    }

    @Test
    fun daemonBindKeepsServiceAfterUnbindUntilStop() {
        EmbeddedService.reset()
        val registry = PrivilegeUserServiceRegistry(TestEmbeddedUserServiceHost())
        val spec = embeddedSpec(daemon = true)

        val first = registry.bind(spec, TestBinder())
        registry.unbind(first.connectionId)
        val second = registry.bind(spec, TestBinder())

        assertEquals(1, EmbeddedService.created)
        registry.unbind(second.connectionId)
        registry.stop(spec)
    }

    @Test
    fun dedicatedProcessDeathClearsConnection() {
        val process = TestUserServiceProcess()
        val registry = PrivilegeUserServiceRegistry(TestDedicatedUserServiceHost(process))
        val spec = dedicatedSpec()

        val result = registry.bind(spec, TestBinder())
        process.killBinder()

        assertThrows(PrivilegeUserServiceException::class.java) {
            registry.unbind(result.connectionId)
        }
    }

    @Test
    fun dedicatedProcessDeathAllowsNextBindToCreateReplacement() {
        val firstProcess = TestUserServiceProcess()
        val host = TestDedicatedUserServiceHost(firstProcess)
        val registry = PrivilegeUserServiceRegistry(host)
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
        val registry = PrivilegeUserServiceRegistry(TestEmbeddedUserServiceHost())
        val client = TestBinder()

        val first = registry.bind(embeddedSpec(tag = "first"), client)
        val second = registry.bind(embeddedSpec(tag = "second"), client)

        assertEquals(2, EmbeddedService.created)
        assertNotSame(first.binder, second.binder)

        registry.unbind(first.connectionId)
        registry.unbind(second.connectionId)
    }

    @Test
    fun embeddedVersionChangeDestroysPreviousInstance() {
        EmbeddedService.reset()
        val registry = PrivilegeUserServiceRegistry(TestEmbeddedUserServiceHost())
        val client = TestBinder()

        val first = registry.bind(embeddedSpec(version = 1), client)
        val second = registry.bind(embeddedSpec(version = 2), client)

        assertEquals(2, EmbeddedService.created)
        assertNotSame(first.binder, second.binder)
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
            val registry = PrivilegeUserServiceRegistry(TestEmbeddedUserServiceHost())
            val client = TestBinder()

            val result = registry.bind(embeddedSpec(), client)

            assertEquals(1, EmbeddedService.created)
            assertEquals(true, result.connectionId.isNotBlank())
        } finally {
            Thread.currentThread().contextClassLoader = originalClassLoader
        }
    }

    @Test
    fun missingEmbeddedClassThrowsDeclarationException() {
        val registry = PrivilegeUserServiceRegistry(TestEmbeddedUserServiceHost())

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
            PrivilegeUserServiceLoader.instantiate(ContextOnlyService::class.java.name)
        }
    }

    @Test
    fun embeddedContextCreationFailureFallsBackToNoArgWhenAvailable() {
        val instance = PrivilegeUserServiceLoader.instantiate(
            serviceClassName = BothConstructorsService::class.java.name,
            contextConfig = PrivilegeUserServiceLoader.ContextConfig(
                packageName = "",
                userId = 0,
                mode = PrivilegeUserServiceLoader.ContextMode.PACKAGE_CONTEXT_ONLY,
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
                    packageName = "",
                    userId = 0,
                    mode = PrivilegeUserServiceLoader.ContextMode.APPLICATION_WITH_PACKAGE_FALLBACK,
                ),
            )
        }
    }

    @Test
    fun makeApplicationPreflightDetectsUnavailablePackageName() {
        assertEquals(
            "LoadedApk.mPackageName is unavailable",
            PrivilegeUserServiceLoader.makeApplicationPreflightFailure(
                FakeLoadedApk(
                    mPackageName = null,
                    mApplicationInfo = Any(),
                ),
            ),
        )
    }

    @Test
    fun makeApplicationPreflightAllowsAvailableLoadedApkState() {
        assertNull(
            PrivilegeUserServiceLoader.makeApplicationPreflightFailure(
                FakeLoadedApk(
                    mPackageName = "priv.kit.sample.debug",
                    mApplicationInfo = Any(),
                ),
            ),
        )
    }

    private fun embeddedSpec(
        tag: String = PrivilegeUserServiceSpec.DEFAULT_TAG,
        version: Int = 1,
        daemon: Boolean = false,
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

    private class FakeLoadedApk(
        @Suppress("MemberVisibilityCanBePrivate")
        val mPackageName: String?,
        @Suppress("MemberVisibilityCanBePrivate")
        val mApplicationInfo: Any?,
    )

}
