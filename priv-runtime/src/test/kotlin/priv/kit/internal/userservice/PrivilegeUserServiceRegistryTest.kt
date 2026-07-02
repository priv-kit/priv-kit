package priv.kit.internal.userservice

import android.content.Context
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.userservice.*
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertNull

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
        val registry = PrivilegeUserServiceRegistry(FakeHost())
        val owner = FakeBinder()
        val spec = embeddedSpec()

        registry.start(spec, owner)
        owner.killBinder()
        val result = registry.bind(spec, FakeBinder())

        assertEquals(2, EmbeddedService.created)
        registry.unbind(result.connectionId)
    }

    @Test
    fun daemonServiceSurvivesOwnerDeath() {
        EmbeddedService.reset()
        val registry = PrivilegeUserServiceRegistry(FakeHost())
        val owner = FakeBinder()
        val spec = embeddedSpec(daemon = true)

        registry.start(spec, owner)
        owner.killBinder()
        val result = registry.bind(spec, FakeBinder())

        assertEquals(1, EmbeddedService.created)
        registry.unbind(result.connectionId)
        registry.stop(spec)
    }

    @Test
    fun daemonBindKeepsServiceAfterUnbindUntilStop() {
        EmbeddedService.reset()
        val registry = PrivilegeUserServiceRegistry(FakeHost())
        val spec = embeddedSpec(daemon = true)

        val first = registry.bind(spec, FakeBinder())
        registry.unbind(first.connectionId)
        val second = registry.bind(spec, FakeBinder())

        assertEquals(1, EmbeddedService.created)
        registry.unbind(second.connectionId)
        registry.stop(spec)
    }

    @Test
    fun dedicatedProcessDeathClearsConnection() {
        val process = FakeDedicatedProcess()
        val registry = PrivilegeUserServiceRegistry(DedicatedFakeHost(process))
        val spec = dedicatedSpec()

        val result = registry.bind(spec, FakeBinder())
        process.kill()

        assertThrows(PrivilegeUserServiceException::class.java) {
            registry.unbind(result.connectionId)
        }
    }

    @Test
    fun dedicatedProcessDeathAllowsNextBindToCreateReplacement() {
        val firstProcess = FakeDedicatedProcess()
        val host = DedicatedFakeHost(firstProcess)
        val registry = PrivilegeUserServiceRegistry(host)
        val spec = dedicatedSpec()

        val first = registry.bind(spec, FakeBinder())
        firstProcess.kill()
        host.process = FakeDedicatedProcess()
        val result = registry.bind(spec, FakeBinder())

        assertNotSame(first.binder, result.binder)
    }

    @Test
    fun embeddedModeCreatesSeparateInstancesForDifferentTags() {
        EmbeddedService.reset()
        val registry = PrivilegeUserServiceRegistry(FakeHost())
        val client = FakeBinder()

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
        val registry = PrivilegeUserServiceRegistry(FakeHost())
        val client = FakeBinder()

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
            val registry = PrivilegeUserServiceRegistry(FakeHost())
            val client = FakeBinder()

            val result = registry.bind(embeddedSpec(), client)

            assertEquals(1, EmbeddedService.created)
            assertEquals(true, result.connectionId.isNotBlank())
        } finally {
            Thread.currentThread().contextClassLoader = originalClassLoader
        }
    }

    @Test
    fun missingEmbeddedClassThrowsDeclarationException() {
        val registry = PrivilegeUserServiceRegistry(FakeHost())

        assertThrows(PrivilegeUserServiceException::class.java) {
            registry.bind(
                PrivilegeUserServiceSpec(
                    serviceClassName = "missing.Service",
                    embedded = true,
                ),
                FakeBinder(),
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
        FakeBinder() {
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
    ) : FakeBinder()

    class BothConstructorsService private constructor(
        val createdWith: String,
    ) : FakeBinder() {
        constructor() : this("no-arg")

        constructor(context: Context) : this(context.packageName)
    }

    private class FakeLoadedApk(
        @Suppress("MemberVisibilityCanBePrivate")
        val mPackageName: String?,
        @Suppress("MemberVisibilityCanBePrivate")
        val mApplicationInfo: Any?,
    )

    private class FakeHost : PrivilegeUserServiceHost {
        override val uid: Int = 0
        override val pid: Int = 1234
        override val packageName: String = "priv.kit.test"
        override val userId: Int = 0

        override fun startDedicatedProcess(
            spec: PrivilegeUserServiceSpec,
            token: String,
        ): Process {
            error("Dedicated process is not used by this test")
        }

        override fun awaitDedicatedProcess(
            token: String,
            timeoutMillis: Long,
        ): IPrivilegeUserServiceProcess {
            error("Dedicated process is not used by this test")
        }

        override fun killDedicatedProcess(process: Process) = Unit
    }

    private class DedicatedFakeHost(
        var process: FakeDedicatedProcess,
    ) : PrivilegeUserServiceHost {
        override val uid: Int = 0
        override val pid: Int = 1234
        override val packageName: String = "priv.kit.test"
        override val userId: Int = 0

        override fun startDedicatedProcess(
            spec: PrivilegeUserServiceSpec,
            token: String,
        ): Process =
            FakeProcess()

        override fun awaitDedicatedProcess(
            token: String,
            timeoutMillis: Long,
        ): IPrivilegeUserServiceProcess = process

        override fun killDedicatedProcess(process: Process) = Unit
    }

    private class FakeDedicatedProcess : IPrivilegeUserServiceProcess {
        private val binder = FakeBinder()

        override fun asBinder(): IBinder = binder

        override fun start() = Unit

        override fun bind(): IBinder = binder

        override fun unbind(connectionId: String) = Unit

        override fun destroy() = Unit

        fun kill() {
            binder.killBinder()
        }
    }

    private class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }

    open class FakeBinder(
        @Volatile
        private var alive: Boolean = true,
    ) : IBinder {
        private val deathRecipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()

        override fun getInterfaceDescriptor(): String = "fake"

        override fun pingBinder(): Boolean = alive

        override fun isBinderAlive(): Boolean = alive

        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun dump(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun dumpAsync(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        open override fun transact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean = alive

        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) {
            if (!alive) {
                throw RemoteException("Binder is dead")
            }
            deathRecipients += recipient
        }

        override fun unlinkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean =
            deathRecipients.remove(recipient)

        fun killBinder() {
            if (!alive) return
            alive = false
            deathRecipients.forEach { it.binderDied() }
            deathRecipients.clear()
        }
    }
}
