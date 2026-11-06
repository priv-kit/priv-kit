package priv.kit.core.internal.userservice

import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.RestrictTo
import priv.kit.core.internal.core.PrivilegeContentProviderCall
import priv.kit.core.internal.core.preparePrivilegeMainLooper
import priv.kit.core.internal.runtime.PrivilegeCrashRecorder
import android.os.Binder
import kotlin.system.exitProcess

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public object PrivilegeUserServiceMain {
    @Keep
    @JvmStatic
    public fun main(args: Array<String>) {
        var crashLog: PrivilegeCrashRecorder? = null
        try {
            val config = Arguments.parse(args)
            crashLog = PrivilegeCrashRecorder.install(
                config.packageName,
                config.userId,
                config.serviceClassName ?: "built-in",
            )
            preparePrivilegeMainLooper()
            val instance = if (config.serviceClassName == null) Binder() else PrivilegeUserServiceLoader.instantiate(
                serviceClassName = config.serviceClassName,
                contextConfig = PrivilegeUserServiceLoader.ContextConfig(
                    packageName = config.packageName,
                    userId = config.userId,
                    mode = PrivilegeUserServiceLoader.ContextMode.APPLICATION_WITH_PACKAGE_FALLBACK,
                ),
            )
            val processBinder = PrivilegeUserServiceProcessBinder(
                serviceClassName = config.serviceClassName,
                instance = instance,
            )
            val accepted = sendReady(config, processBinder)
            if (!accepted) {
                System.err.println("Privilege UserService handshake was rejected")
                exitProcess(2)
            }
            Looper.loop()
        } catch (throwable: Throwable) {
            crashLog?.recordOrLog(Thread.currentThread(), throwable)
            Log.e(TAG, "Privilege UserService failed", throwable)
            throwable.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private fun sendReady(
        config: Arguments,
        processBinder: PrivilegeUserServiceProcessBinder,
    ): Boolean {
        val response = PrivilegeContentProviderCall.call(
            authority = config.providerAuthority,
            method = PrivilegeUserServiceContract.METHOD_USER_SERVICE_READY,
            arg = config.token,
            extras = Bundle().apply {
                putString(PrivilegeUserServiceContract.EXTRA_TOKEN, config.token)
                putBinder(PrivilegeUserServiceContract.EXTRA_PROCESS_BINDER, processBinder.asBinder())
            },
            userId = config.userId,
            logTag = TAG,
        )
        return response?.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, false) == true
    }

    private data class Arguments(
        val token: String,
        val providerAuthority: String,
        val packageName: String,
        val userId: Int,
        val serviceClassName: String?,
    ) {
        companion object {
            fun parse(args: Array<String>): Arguments {
                val values = mutableMapOf<String, String>()
                var index = 0
                while (index < args.size) {
                    val key = args[index]
                    require(key.startsWith("--")) { "Unexpected argument: $key" }
                    val valueIndex = index + 1
                    require(valueIndex < args.size) { "Missing value for $key" }
                    values[key.removePrefix("--")] = args[valueIndex]
                    index += 2
                }
                return Arguments(
                    token = values.required("token"),
                    providerAuthority = values.required("provider-authority"),
                    packageName = values.required("package-name"),
                    userId = values.optionalInt("user-id", 0),
                    serviceClassName = values["service-class"],
                )
            }

            private fun Map<String, String>.required(key: String): String =
                requireNotNull(this[key]?.takeIf { it.isNotBlank() }) { "Missing required argument --$key" }

            private fun Map<String, String>.optionalInt(
                key: String,
                defaultValue: Int,
            ): Int {
                val rawValue = this[key] ?: return defaultValue
                return rawValue.toIntOrNull()
                    ?: throw IllegalArgumentException("--$key must be an integer")
            }
        }
    }

    private const val TAG = "PrivKitUserService"
}
