package priv.kit.userservice

import android.app.IActivityManager
import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import android.util.Log
import java.lang.reflect.InvocationTargetException

internal object PrivilegeUserServiceProviderCall {
    fun call(
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle,
        userId: Int = USER_SYSTEM,
    ): Bundle? {
        val activityManager = activityManager()
        val providerToken = Binder()
        val holder = getContentProviderExternal(
            activityManager = activityManager,
            authority = authority,
            userId = userId,
            token = providerToken,
        ) ?: throw IllegalStateException("Content provider not found: $authority")

        return try {
            val provider = providerFrom(holder)
            invokeProviderCall(
                provider = provider,
                callingPackage = callingPackageName(),
                authority = authority,
                method = method,
                arg = arg,
                extras = extras,
            )
        } finally {
            removeContentProviderExternal(
                activityManager = activityManager,
                authority = authority,
                userId = userId,
                token = providerToken,
            )
        }
    }

    private fun activityManager(): IActivityManager {
        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
            ?: throw IllegalStateException("Activity service was not found")
        return IActivityManager.Stub.asInterface(binder)
            ?: throw IllegalStateException("Activity service returned an invalid Binder")
    }

    private fun getContentProviderExternal(
        activityManager: Any,
        authority: String,
        userId: Int,
        token: IBinder,
    ): Any? {
        val candidates = listOf(
            ReflectCall(
                methodName = "getContentProviderExternal",
                parameterTypes = arrayOf(
                    String::class.java,
                    Integer.TYPE,
                    IBinder::class.java,
                    String::class.java,
                ),
                arguments = arrayOf(authority, userId, token, authority),
            ),
            ReflectCall(
                methodName = "getContentProviderExternal",
                parameterTypes = arrayOf(
                    String::class.java,
                    Integer.TYPE,
                    IBinder::class.java,
                ),
                arguments = arrayOf(authority, userId, token),
            ),
        )
        return invokeFirstExisting(activityManager, candidates)
    }

    private fun providerFrom(holder: Any): Any {
        val providerField = runCatching { holder.javaClass.getField("provider") }.getOrNull()
        return if (providerField != null) {
            providerField.get(holder)
                ?: throw IllegalStateException("Content provider holder has no provider")
        } else {
            holder
        }
    }

    private fun invokeProviderCall(
        provider: Any,
        callingPackage: String?,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle,
    ): Bundle? {
        val candidates = providerCallCandidates(
            callingPackage = callingPackage,
            authority = authority,
            method = method,
            arg = arg,
            extras = extras,
        )
        var linkageError: LinkageError? = null
        for (candidate in candidates) {
            val callMethod = runCatching {
                provider.javaClass.getMethod("call", *candidate.parameterTypes)
            }.getOrNull() ?: continue

            try {
                return callMethod.invoke(provider, *candidate.arguments) as Bundle?
            } catch (exception: InvocationTargetException) {
                val cause = exception.targetException
                if (cause is LinkageError) {
                    linkageError = cause
                    continue
                }
                throw cause
            }
        }

        linkageError?.let { throw it }
        throw NoSuchMethodException("No supported IContentProvider.call signature")
    }

    private fun providerCallCandidates(
        callingPackage: String?,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle,
    ): List<ReflectCall> {
        val candidates = mutableListOf<ReflectCall>()
        val attributionSource = createAttributionSource(callingPackage)
        if (attributionSource != null) {
            candidates += ReflectCall(
                methodName = "call",
                parameterTypes = arrayOf(
                    attributionSource.javaClass,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Bundle::class.java,
                ),
                arguments = arrayOf(attributionSource, authority, method, arg, extras),
            )
        }
        candidates += ReflectCall(
            methodName = "call",
            parameterTypes = arrayOf(
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java,
            ),
            arguments = arrayOf(callingPackage, null, authority, method, arg, extras),
        )
        candidates += ReflectCall(
            methodName = "call",
            parameterTypes = arrayOf(
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java,
            ),
            arguments = arrayOf(callingPackage, authority, method, arg, extras),
        )
        candidates += ReflectCall(
            methodName = "call",
            parameterTypes = arrayOf(
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java,
            ),
            arguments = arrayOf(callingPackage, method, arg, extras),
        )
        return candidates
    }

    private fun createAttributionSource(packageName: String?): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null
        }
        return AttributionSource.Builder(Process.myUid())
            .setPackageName(packageName)
            .setAttributionTag(null)
            .build()
    }

    private fun removeContentProviderExternal(
        activityManager: Any,
        authority: String,
        userId: Int,
        token: IBinder,
    ) {
        val candidates = listOf(
            ReflectCall(
                methodName = "removeContentProviderExternal",
                parameterTypes = arrayOf(String::class.java, IBinder::class.java),
                arguments = arrayOf(authority, token),
            ),
            ReflectCall(
                methodName = "removeContentProviderExternalAsUser",
                parameterTypes = arrayOf(String::class.java, IBinder::class.java, Integer.TYPE),
                arguments = arrayOf(authority, token, userId),
            ),
            ReflectCall(
                methodName = "removeContentProviderExternal",
                parameterTypes = arrayOf(String::class.java, IBinder::class.java, Integer.TYPE),
                arguments = arrayOf(authority, token, userId),
            ),
        )
        runCatching {
            invokeFirstExisting(activityManager, candidates)
        }.onFailure {
            Log.w(TAG, "Failed to release content provider authority=$authority", it)
        }
    }

    private fun invokeFirstExisting(
        target: Any,
        candidates: List<ReflectCall>,
    ): Any? {
        for (candidate in candidates) {
            val method = runCatching {
                target.javaClass.getMethod(candidate.methodName, *candidate.parameterTypes)
            }.getOrNull() ?: continue
            try {
                return method.invoke(target, *candidate.arguments)
            } catch (exception: InvocationTargetException) {
                throw exception.targetException
            }
        }
        throw NoSuchMethodException(
            "No supported ${candidates.firstOrNull()?.methodName.orEmpty()} signature",
        )
    }

    private fun callingPackageName(): String? =
        if (Process.myUid() == SHELL_UID) SHELL_PACKAGE_NAME else null

    private class ReflectCall(
        val methodName: String,
        val parameterTypes: Array<Class<*>>,
        val arguments: Array<Any?>,
    )

    private const val USER_SYSTEM = 0
    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE_NAME = "com.android.shell"
    private const val TAG = "PrivKitUserService"
}
