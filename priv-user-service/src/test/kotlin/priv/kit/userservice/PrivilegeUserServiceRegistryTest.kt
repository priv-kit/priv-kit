package priv.kit.userservice

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PrivilegeUserServiceRegistryTest {
    @Test
    fun specDefaultsToDedicatedProcess() {
        val spec = PrivilegeUserServiceSpec(serviceClassName = EmbeddedService::class.java.name)

        assertEquals(PrivilegeUserServiceProcessMode.DEDICATED_PROCESS, spec.processMode)
        assertEquals(PrivilegeUserServiceOwnerDeathPolicy.DESTROY_ON_OWNER_DEATH, spec.ownerDeathPolicy)
        assertEquals(10_000L, spec.destroyTimeoutMillis)
    }

    @Test
    fun specAllowsNegativeDestroyTimeoutToDisableDedicatedFallback() {
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = EmbeddedService::class.java.name,
            destroyTimeoutMillis = -1L,
        )

        assertEquals(-1L, spec.destroyTimeoutMillis)
    }

    @Test
    fun negativeDestroyTimeoutSkipsDedicatedExitFallback() {
        val process = FakeDedicatedProcess()
        val host = DedicatedFakeHost(process)
        val registry = PrivilegeUserServiceRegistry(host)
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = EmbeddedService::class.java.name,
            processMode = PrivilegeUserServiceProcessMode.DEDICATED_PROCESS,
            destroyTimeoutMillis = -1L,
        )

        registry.start(spec, FakeBinder())
        registry.stop(spec)

        assertEquals(true, process.destroyed.await(1, TimeUnit.SECONDS))
        Thread.sleep(50L)
        assertEquals(1, process.destroyCalls.get())
        assertEquals(0, host.awaitExitCalls.get())
        assertEquals(0, host.killCalls.get())
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
        assertEquals(1, first.status.boundCount)
        assertEquals(1, second.status.boundCount)

        registry.unbind(first.connectionId)
        registry.unbind(second.connectionId)

        assertEquals(PrivilegeUserServiceState.STOPPED, registry.getStatus(embeddedSpec(tag = "first")).state)
        assertEquals(PrivilegeUserServiceState.STOPPED, registry.getStatus(embeddedSpec(tag = "second")).state)
    }

    @Test
    fun embeddedVersionChangeDestroysPreviousInstance() {
        EmbeddedService.reset()
        val registry = PrivilegeUserServiceRegistry(FakeHost())
        val client = FakeBinder()

        registry.bind(embeddedSpec(version = 1), client)
        registry.bind(embeddedSpec(version = 2), client)

        assertEquals(2, EmbeddedService.created)
        assertEquals(2, registry.getStatus(embeddedSpec(version = 2)).version)
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
            assertEquals(1, result.status.boundCount)
        } finally {
            Thread.currentThread().contextClassLoader = originalClassLoader
        }
    }

    @Test
    fun missingEmbeddedClassThrowsDeclarationException() {
        val registry = PrivilegeUserServiceRegistry(FakeHost())

        assertThrows(PrivilegeUserServiceDeclarationException::class.java) {
            registry.bind(
                PrivilegeUserServiceSpec(
                    serviceClassName = "missing.Service",
                    processMode = PrivilegeUserServiceProcessMode.IN_SERVER_PROCESS,
                ),
                FakeBinder(),
            )
        }
    }

    private fun embeddedSpec(
        tag: String = PrivilegeUserServiceSpec.DEFAULT_TAG,
        version: Int = 1,
    ): PrivilegeUserServiceSpec =
        PrivilegeUserServiceSpec(
            serviceClassName = EmbeddedService::class.java.name,
            tag = tag,
            version = version,
            processMode = PrivilegeUserServiceProcessMode.IN_SERVER_PROCESS,
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

    private class FakeHost : PrivilegeUserServiceHost {
        override val uid: Int = 0
        override val pid: Int = 1234

        override fun startDedicatedProcess(
            spec: PrivilegeUserServiceSpec,
            token: String,
        ): PrivilegeUserServiceProcessHandle {
            error("Dedicated process is not used by this test")
        }

        override fun awaitDedicatedProcess(
            token: String,
            timeoutMillis: Long,
        ): IPrivilegeUserServiceProcess {
            error("Dedicated process is not used by this test")
        }

        override fun awaitDedicatedProcessExit(
            handle: PrivilegeUserServiceProcessHandle,
            timeoutMillis: Long,
        ): Boolean = true

        override fun killDedicatedProcess(handle: PrivilegeUserServiceProcessHandle) = Unit
    }

    private class DedicatedFakeHost(
        private val process: FakeDedicatedProcess,
    ) : PrivilegeUserServiceHost {
        val awaitExitCalls = AtomicInteger(0)
        val killCalls = AtomicInteger(0)

        override val uid: Int = 0
        override val pid: Int = 1234

        override fun startDedicatedProcess(
            spec: PrivilegeUserServiceSpec,
            token: String,
        ): PrivilegeUserServiceProcessHandle =
            PrivilegeUserServiceProcessHandle(FakeProcess())

        override fun awaitDedicatedProcess(
            token: String,
            timeoutMillis: Long,
        ): IPrivilegeUserServiceProcess = process

        override fun awaitDedicatedProcessExit(
            handle: PrivilegeUserServiceProcessHandle,
            timeoutMillis: Long,
        ): Boolean {
            awaitExitCalls.incrementAndGet()
            return false
        }

        override fun killDedicatedProcess(handle: PrivilegeUserServiceProcessHandle) {
            killCalls.incrementAndGet()
        }
    }

    private class FakeDedicatedProcess : IPrivilegeUserServiceProcess {
        val destroyed = CountDownLatch(1)
        val destroyCalls = AtomicInteger(0)
        private val binder = FakeBinder()

        override fun asBinder(): IBinder = binder

        override fun getPid(): Int = 4321

        override fun start() = Unit

        override fun bind(): IBinder = binder

        override fun unbind(connectionId: String) = Unit

        override fun destroy() {
            destroyCalls.incrementAndGet()
            destroyed.countDown()
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
    }
}
