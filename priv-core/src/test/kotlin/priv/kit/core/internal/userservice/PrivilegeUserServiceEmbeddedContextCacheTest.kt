package priv.kit.core.internal.userservice

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.TestEmbeddedUserServiceHost
import priv.kit.core.userservice.PrivilegeUserServiceSpec

@RunWith(RobolectricTestRunner::class)
class PrivilegeUserServiceEmbeddedContextCacheTest {
    @Test
    fun embeddedContextAndClassLoaderAreCreatedOnceAndReused() {
        ContextService.reset()
        val context = RuntimeEnvironment.getApplication()
        val classLoader = context.classLoader
        var runtimeCreations = 0
        val contextRuntime by lazy {
            runtimeCreations += 1
            PrivilegeUserServiceLoader.ContextRuntime(
                context = context,
                classLoader = classLoader,
            )
        }
        val registry = PrivilegeUserServiceRegistry(
            host = TestEmbeddedUserServiceHost(),
            embeddedContextRuntimeProvider = { contextRuntime },
        )
        val client = TestBinder()

        val first = registry.bind(contextSpec("first"), client)
        val second = registry.bind(contextSpec("second"), client)

        assertEquals(1, runtimeCreations)
        assertEquals(2, ContextService.contexts.size)
        ContextService.contexts.forEach { assertSame(context, it) }
        ContextService.threadClassLoaders.forEach { assertSame(classLoader, it) }

        registry.unbind(first.connectionId)
        registry.unbind(second.connectionId)
    }

    @Test
    fun noArgEmbeddedServiceDoesNotCreateContextCache() {
        var providerCalls = 0
        val registry = PrivilegeUserServiceRegistry(
            host = TestEmbeddedUserServiceHost(),
            embeddedContextRuntimeProvider = {
                providerCalls += 1
                error("No-arg UserService must not request a Context")
            },
        )

        val connection = registry.bind(
            PrivilegeUserServiceSpec(
                serviceClassName = NoArgService::class.java.name,
                embedded = true,
            ),
            TestBinder(),
        )

        assertEquals(0, providerCalls)
        registry.unbind(connection.connectionId)
    }

    private fun contextSpec(tag: String): PrivilegeUserServiceSpec =
        PrivilegeUserServiceSpec(
            serviceClassName = ContextService::class.java.name,
            tag = tag,
            embedded = true,
        )

    class ContextService(
        context: Context,
    ) : TestBinder() {
        init {
            contexts += context
            threadClassLoaders += Thread.currentThread().contextClassLoader
        }

        companion object {
            val contexts = mutableListOf<Context>()
            val threadClassLoaders = mutableListOf<ClassLoader?>()

            fun reset() {
                contexts.clear()
                threadClassLoaders.clear()
            }
        }
    }

    class NoArgService : TestBinder()
}
