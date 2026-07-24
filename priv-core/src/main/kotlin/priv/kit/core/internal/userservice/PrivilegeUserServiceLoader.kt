package priv.kit.core.internal.userservice

import android.app.ActivityThread
import android.app.Application
import android.content.Context
import android.content.res.CompatibilityInfo
import android.os.Looper
import android.os.UserHandleHidden
import android.util.Log
import priv.kit.core.internal.hidden.castedHidden
import priv.kit.core.userservice.PrivilegeUserServiceException
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException

internal object PrivilegeUserServiceLoader {
    fun prepareContextRuntime() {
        runCatching {
            activityThread()
        }.onFailure { throwable ->
            runCatching {
                Log.w(TAG, "UserService Context runtime could not be prepared", throwable)
            }
        }
    }

    internal fun createPackageContext(
        packageName: String,
        userId: Int,
    ): Context {
        return createPackageContext(
            activityThread = activityThread(),
            packageName = packageName,
            userId = userId,
        )
    }

    fun instantiate(
        serviceClassName: String,
        contextConfig: ContextConfig?,
    ): Any {
        val clazz = loadClass(
            serviceClassName = serviceClassName,
            preferredClassLoaders = emptyList(),
        )
        val contextConstructor = contextConstructor(clazz)
        return if (contextConstructor != null) {
            instantiateWithContext(
                initialClass = clazz,
                serviceClassName = serviceClassName,
                contextConfig = contextConfig,
            )
        } else {
            instantiateWithNoArgConstructor(clazz, serviceClassName)
        }
    }

    private fun instantiateWithContext(
        initialClass: Class<*>,
        serviceClassName: String,
        contextConfig: ContextConfig?,
    ): Any {
        val config = contextConfig ?: throw PrivilegeUserServiceException(
            "UserService declares a Context constructor, but no Context config is available: $serviceClassName",
        )

        val contextRuntime = try {
            createUserServiceContext(config)
        } catch (throwable: Throwable) {
            return fallbackToNoArgForEmbeddedContextFailure(
                initialClass = initialClass,
                serviceClassName = serviceClassName,
                config = config,
                throwable = throwable,
            )
        }
        val context = contextRuntime.context
        val classLoader = contextRuntime.classLoader
        val clazz = loadClass(
            serviceClassName = serviceClassName,
            preferredClassLoaders = listOf(classLoader),
        )
        val constructor = contextConstructor(clazz) ?: throw PrivilegeUserServiceException(
            "UserService must have an accessible Context constructor: $serviceClassName",
        )

        return withContextClassLoader(classLoader) {
            try {
                constructor.newInstance(context)
            } catch (throwable: Throwable) {
                throw PrivilegeUserServiceException(
                    "UserService Context constructor failed: $serviceClassName",
                    throwable,
                )
            }
        }
    }

    private fun instantiateWithNoArgConstructor(
        clazz: Class<*>,
        serviceClassName: String,
    ): Any {
        val constructor = noArgConstructor(clazz) ?: throw PrivilegeUserServiceException(
            "UserService must have an accessible no-arg constructor: $serviceClassName",
        )
        return instantiateWithNoArgConstructor(constructor, serviceClassName)
    }

    private fun instantiateWithNoArgConstructor(
        constructor: Constructor<*>,
        serviceClassName: String,
    ): Any =
        try {
            constructor.newInstance()
        } catch (throwable: Throwable) {
            throw PrivilegeUserServiceException(
                "UserService must have an accessible no-arg constructor: $serviceClassName",
                throwable,
            )
        }

    private fun fallbackToNoArgForEmbeddedContextFailure(
        initialClass: Class<*>,
        serviceClassName: String,
        config: ContextConfig,
        throwable: Throwable,
    ): Any {
        val noArgConstructor = if (config.mode == ContextMode.PACKAGE_CONTEXT_ONLY) {
            noArgConstructor(initialClass)
        } else {
            null
        }
        if (noArgConstructor != null) {
            logFallbackWarning(
                "Package Context could not be created for embedded UserService $serviceClassName; " +
                    "falling back to no-arg constructor",
                throwable,
            )
            return instantiateWithNoArgConstructor(noArgConstructor, serviceClassName)
        }

        throw PrivilegeUserServiceException(
            "UserService Context could not be created: $serviceClassName",
            throwable,
        )
    }

    private fun createUserServiceContext(config: ContextConfig): ContextRuntime {
        config.contextRuntimeProvider?.let { provider ->
            return provider()
        }
        val activityThread = activityThread()
        val packageContext = createPackageContext(
            activityThread = activityThread,
            packageName = config.packageName,
            userId = config.userId,
        )
        val context = if (config.mode == ContextMode.PACKAGE_CONTEXT_ONLY) {
            packageContext
        } else {
            // Some vendor builds throw from makeApplication in app_process UserService children.
            try {
                makeApplication(activityThread, packageContext)
            } catch (throwable: Throwable) {
                logBestEffortFallback(
                    "makeApplication unavailable for UserService package=${config.packageName}; " +
                        "using package Context fallback",
                    throwable,
                )
                packageContext
            }
        }
        return ContextRuntime(
            context = context,
            classLoader = context.classLoader,
        )
    }

    private fun activityThread(): ActivityThread {
        val current = runCatching {
            ActivityThread.currentActivityThread()
        }.getOrNull()
        if (current != null) return current
        check(Looper.myLooper() != null) {
            "ActivityThread is not initialized and current thread has no Looper"
        }
        return ActivityThread.systemMain()
            ?: throw IllegalStateException("ActivityThread.systemMain returned null")
    }

    private fun createPackageContext(
        activityThread: ActivityThread,
        packageName: String,
        userId: Int,
    ): Context {
        val systemContext = activityThread.systemContext
        val userHandle = UserHandleHidden.of(userId)
        return systemContext.castedHidden.createPackageContextAsUser(
            packageName,
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            userHandle,
        )
    }

    private fun makeApplication(
        activityThread: ActivityThread,
        packageContext: Context,
    ): Application {
        val loadedApk = activityThread.getPackageInfoNoCheck(
            packageContext.applicationInfo,
            CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO,
        )
        makeApplicationPreflightFailure(
            packageName = loadedApk.packageName,
            hasApplicationInfo = loadedApk.applicationInfo != null,
        )?.let { reason ->
            throw IllegalStateException(reason)
        }
        return loadedApk.makeApplication(true, null)
    }

    internal fun makeApplicationPreflightFailure(
        packageName: String?,
        hasApplicationInfo: Boolean,
    ): String? {
        if (packageName.isNullOrBlank()) {
            return "LoadedApk.mPackageName is unavailable"
        }

        if (!hasApplicationInfo) {
            return "LoadedApk.mApplicationInfo is unavailable"
        }

        return null
    }

    private fun contextConstructor(clazz: Class<*>) =
        runCatching {
            clazz.getDeclaredConstructor(Context::class.java).apply {
                isAccessible = true
            }
        }.getOrNull()

    private fun noArgConstructor(clazz: Class<*>) =
        runCatching {
            clazz.getDeclaredConstructor().apply {
                isAccessible = true
            }
        }.getOrNull()

    private fun loadClass(
        serviceClassName: String,
        preferredClassLoaders: List<ClassLoader>,
    ): Class<*> {
        val classLoaders = (
            preferredClassLoaders +
                listOfNotNull(
                    Thread.currentThread().contextClassLoader,
                    PrivilegeUserServiceLoader::class.java.classLoader,
                )
            ).distinct()

        var failure: Throwable? = null
        classLoaders.forEach { classLoader ->
            try {
                return Class.forName(serviceClassName, false, classLoader)
            } catch (throwable: ClassNotFoundException) {
                val previousFailure = failure
                if (previousFailure == null) {
                    failure = throwable
                } else {
                    previousFailure.addSuppressed(throwable)
                }
            }
        }

        throw PrivilegeUserServiceException(
            "UserService class was not found: $serviceClassName",
            failure,
        )
    }

    private inline fun <T> withContextClassLoader(
        classLoader: ClassLoader,
        block: () -> T,
    ): T {
        val thread = Thread.currentThread()
        val previousClassLoader = thread.contextClassLoader
        thread.contextClassLoader = classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previousClassLoader
        }
    }

    private fun logFallbackWarning(
        message: String,
        throwable: Throwable,
    ) {
        runCatching {
            Log.w(TAG, "$message: ${throwable.compactDescription()}")
        }
    }

    private fun logBestEffortFallback(
        message: String,
        throwable: Throwable,
    ) {
        runCatching {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "$message: ${throwable.compactDescription()}")
            }
        }
    }

    private fun Throwable.compactDescription(): String {
        val actual = if (this is InvocationTargetException) {
            targetException ?: this
        } else {
            this
        }
        val detail = actual.message?.takeIf { it.isNotBlank() }
        return if (detail == null) {
            actual.javaClass.name
        } else {
            "${actual.javaClass.name}: $detail"
        }
    }

    internal data class ContextConfig(
        val packageName: String,
        val userId: Int,
        val mode: ContextMode,
        val contextRuntimeProvider: (() -> ContextRuntime)? = null,
    )

    internal data class ContextRuntime(
        val context: Context,
        val classLoader: ClassLoader,
    )

    internal enum class ContextMode {
        PACKAGE_CONTEXT_ONLY,
        APPLICATION_WITH_PACKAGE_FALLBACK,
    }

    private const val TAG = "PrivKitUserService"
}
