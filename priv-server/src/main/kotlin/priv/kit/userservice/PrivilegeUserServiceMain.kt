package priv.kit.userservice

import android.os.Bundle
import android.os.Looper
import android.util.Log
import java.io.File
import kotlin.system.exitProcess

public object PrivilegeUserServiceMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        try {
            prepareMainLooper()
            val config = Arguments.parse(args)
            val instance = PrivilegeUserServiceLoader.instantiate(
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
            watchServer(config.serverPid)
            Looper.loop()
        } catch (throwable: Throwable) {
            Log.e(TAG, "Privilege UserService failed", throwable)
            throwable.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private fun sendReady(
        config: Arguments,
        processBinder: PrivilegeUserServiceProcessBinder,
    ): Boolean {
        val response = PrivilegeUserServiceProviderCall.call(
            authority = config.providerAuthority,
            method = PrivilegeUserServiceContract.METHOD_USER_SERVICE_READY,
            arg = config.token,
            extras = Bundle().apply {
                putString(PrivilegeUserServiceContract.EXTRA_TOKEN, config.token)
                putBinder(PrivilegeUserServiceContract.EXTRA_PROCESS_BINDER, processBinder.asBinder())
            },
            userId = config.userId,
        )
        return response?.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, false) == true
    }

    private fun watchServer(serverPid: Int) {
        if (serverPid <= 0) return
        Thread {
            while (true) {
                Thread.sleep(SERVER_WATCH_INTERVAL_MILLIS)
                if (!File("/proc/$serverPid").exists()) {
                    Log.i(TAG, "Privilege server process disappeared; exiting UserService")
                    exitProcess(0)
                }
            }
        }.apply {
            name = "priv-kit-user-service-server-watch"
            isDaemon = true
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun prepareMainLooper() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper()
        }
    }

    private data class Arguments(
        val token: String,
        val providerAuthority: String,
        val packageName: String,
        val userId: Int,
        val serviceClassName: String,
        val serverPid: Int,
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
                    serviceClassName = values.required("service-class"),
                    serverPid = values.requiredInt("server-pid"),
                )
            }

            private fun Map<String, String>.required(key: String): String =
                requireNotNull(this[key]?.takeIf { it.isNotBlank() }) { "Missing required argument --$key" }

            private fun Map<String, String>.requiredInt(key: String): Int {
                val rawValue = required(key)
                return rawValue.toIntOrNull()
                    ?: throw IllegalArgumentException("--$key must be an integer")
            }

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
    private const val SERVER_WATCH_INTERVAL_MILLIS = 1_000L
}
