package priv.kit.internal.userservice

import android.app.ActivityThread
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Looper
import android.os.UserHandle
import android.os.UserHandleHidden
import android.util.Log
import priv.kit.internal.hidden.castedHidden
import priv.kit.userservice.PrivilegeUserServiceDeclarationException
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal object PrivilegeUserServiceLoader {
    fun prepareContextRuntime() {
        runCatching {
            activityThread()
        }.onFailure { throwable ->
            logWarning("UserService Context runtime could not be prepared", throwable)
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
        contextConfig: ContextConfig? = null,
    ): Any {
        val clazz = loadClass(serviceClassName)
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
        val config = contextConfig ?: throw PrivilegeUserServiceDeclarationException(
            "UserService declares a Context constructor, but no Context config is available: $serviceClassName",
        )

        val context = try {
            createUserServiceContext(config)
        } catch (throwable: Throwable) {
            return fallbackToNoArgForEmbeddedContextFailure(
                initialClass = initialClass,
                serviceClassName = serviceClassName,
                config = config,
                throwable = throwable,
            )
        }
        val clazz = loadClass(
            serviceClassName = serviceClassName,
            preferredClassLoaders = listOf(context.classLoader),
        )
        val constructor = contextConstructor(clazz) ?: throw PrivilegeUserServiceDeclarationException(
            "UserService must have an accessible Context constructor: $serviceClassName",
        )

        return withContextClassLoader(context.classLoader) {
            try {
                constructor.newInstance(context)
            } catch (throwable: Throwable) {
                throw PrivilegeUserServiceDeclarationException(
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
        val constructor = noArgConstructor(clazz) ?: throw PrivilegeUserServiceDeclarationException(
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
            throw PrivilegeUserServiceDeclarationException(
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

        throw PrivilegeUserServiceDeclarationException(
            "UserService Context could not be created: $serviceClassName",
            throwable,
        )
    }

    private fun createUserServiceContext(config: ContextConfig): Context {
        require(config.packageName.isNotBlank()) {
            "UserService Context creation requires a package name"
        }
        val activityThread = activityThread()
        val packageContext = createPackageContext(
            activityThread = activityThread,
            packageName = config.packageName,
            userId = config.userId,
        )
        if (config.mode == ContextMode.PACKAGE_CONTEXT_ONLY) {
            return packageContext
        }

        // Some vendor builds throw from makeApplication in app_process UserService children.
        return try {
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
        val systemContext = findMethod(activityThread.javaClass, "getSystemContext")
            ?.invoke(activityThread) as? Context
            ?: throw IllegalStateException("ActivityThread system Context is unavailable")
        val userHandle = createUserHandle(userId)
        return systemContext.castedHidden.createPackageContextAsUser(
            packageName,
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            userHandle,
        )
    }

    private fun createUserHandle(userId: Int): UserHandle = UserHandleHidden.of(userId)

    private fun makeApplication(
        activityThread: ActivityThread,
        packageContext: Context,
    ): Application {
        val loadedApk = findField(packageContext.javaClass, "mPackageInfo")
            ?.get(packageContext)
            ?: throw NoSuchFieldException("ContextImpl.mPackageInfo")
        makeApplicationPreflightFailure(loadedApk)?.let { reason ->
            throw IllegalStateException(reason)
        }
        val makeApplication = findMethod(
            loadedApk.javaClass,
            "makeApplication",
            Boolean::class.javaPrimitiveType!!,
            Instrumentation::class.java,
        ) ?: throw NoSuchMethodException("LoadedApk.makeApplication")
        val application = makeApplication.invoke(loadedApk, true, null) as Application
        findField(activityThread.javaClass, "mInitialApplication")
            ?.set(activityThread, application)
        return application
    }

    internal fun makeApplicationPreflightFailure(loadedApk: Any): String? {
        val packageNameField = findField(loadedApk.javaClass, "mPackageName")
        if (packageNameField != null) {
            val packageName = packageNameField.get(loadedApk) as? String
            if (packageName.isNullOrBlank()) {
                return "LoadedApk.mPackageName is unavailable"
            }
        }

        val applicationInfoField = findField(loadedApk.javaClass, "mApplicationInfo")
        if (applicationInfoField != null && applicationInfoField.get(loadedApk) == null) {
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
        preferredClassLoaders: List<ClassLoader> = emptyList(),
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

        throw PrivilegeUserServiceDeclarationException(
            "UserService class was not found: $serviceClassName",
            failure,
        )
    }

    private fun findMethod(
        type: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val method = runCatching {
                current.getDeclaredMethod(name, *parameterTypes)
            }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    private fun findField(
        type: Class<*>,
        name: String,
    ): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching {
                current.getDeclaredField(name)
            }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
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

    private fun logWarning(
        message: String,
        throwable: Throwable,
    ) {
        runCatching {
            Log.w(TAG, message, throwable)
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
    )

    internal enum class ContextMode {
        PACKAGE_CONTEXT_ONLY,
        APPLICATION_WITH_PACKAGE_FALLBACK,
    }

    private const val TAG = "PrivKitUserService"
}
