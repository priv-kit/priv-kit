package priv.kit.server

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import priv.kit.userservice.IPrivilegeUserServiceProcess
import priv.kit.userservice.PrivilegeUserServiceContract
import priv.kit.userservice.PrivilegeUserServiceHost
import priv.kit.userservice.PrivilegeUserServiceProcessHandle
import priv.kit.userservice.PrivilegeUserServiceSpec
import priv.kit.userservice.PrivilegeUserServiceStartException
import java.util.concurrent.TimeUnit

internal class PrivilegeServerUserServiceHost(
    private val config: PrivilegeServerConfig,
) : PrivilegeUserServiceHost {
    override val uid: Int
        get() = Process.myUid()

    override val pid: Int
        get() = Process.myPid()

    override val packageName: String
        get() = config.packageName

    override val userId: Int
        get() = config.userId

    override fun startDedicatedProcess(
        spec: PrivilegeUserServiceSpec,
        token: String,
    ): PrivilegeUserServiceProcessHandle {
        val classpath = config.classpath.ifBlank {
            throw PrivilegeUserServiceStartException("Server classpath is unavailable")
        }
        val process = ProcessBuilder(
            "/system/bin/app_process",
            "/system/bin",
            "--nice-name=${buildProcessName(spec)}",
            USER_SERVICE_MAIN_CLASS,
            "--token",
            token,
            "--provider-authority",
            config.providerAuthority,
            "--package-name",
            config.packageName,
            "--user-id",
            config.userId.toString(),
            "--service-class",
            spec.serviceClassName,
            "--server-pid",
            Process.myPid().toString(),
        ).apply {
            environment()["CLASSPATH"] = classpath
            redirectErrorStream(true)
        }.start()
        return PrivilegeUserServiceProcessHandle(process)
    }

    override fun awaitDedicatedProcess(
        token: String,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val response = runCatching {
                PrivilegeServerProviderCall.call(
                    authority = config.providerAuthority,
                    method = PrivilegeUserServiceContract.METHOD_USER_SERVICE_CLAIM,
                    arg = token,
                    extras = Bundle().apply {
                        putString(PrivilegeUserServiceContract.EXTRA_TOKEN, token)
                    },
                    userId = config.userId,
                )
            }.onFailure {
                lastFailure = it
            }.getOrNull()

            val processBinder = response
                ?.takeIf { it.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, false) }
                ?.getBinder(PrivilegeUserServiceContract.EXTRA_PROCESS_BINDER)
            if (processBinder != null) {
                return IPrivilegeUserServiceProcess.Stub.asInterface(processBinder)
                    ?: throw PrivilegeUserServiceStartException("UserService process returned an invalid Binder")
            }
            Thread.sleep(CLAIM_RETRY_DELAY_MILLIS)
        }
        throw PrivilegeUserServiceStartException(
            "Timed out waiting for dedicated UserService process",
            lastFailure,
        )
    }

    override fun killDedicatedProcess(handle: PrivilegeUserServiceProcessHandle) {
        runCatching {
            handle.process.destroyForcibly()
        }.onFailure {
            runCatching {
                handle.process.destroy()
            }
        }
    }

    override fun awaitDedicatedProcessExit(
        handle: PrivilegeUserServiceProcessHandle,
        timeoutMillis: Long,
    ): Boolean {
        if (timeoutMillis <= 0L) return !handle.process.isAlive
        return try {
            handle.process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            !handle.process.isAlive
        }
    }

    private fun buildProcessName(spec: PrivilegeUserServiceSpec): String {
        val suffix = (spec.serviceClassName.substringAfterLast('.') + "-" + spec.tag)
            .map { char ->
                if (char.isLetterOrDigit() || char == '_' || char == '-') char else '-'
            }
            .joinToString("")
            .take(48)
            .ifBlank { "user-service" }
        return "${config.packageName}:priv-kit-us:$suffix"
    }

    private companion object {
        const val USER_SERVICE_MAIN_CLASS = "priv.kit.userservice.PrivilegeUserServiceMain"
        const val CLAIM_RETRY_DELAY_MILLIS = 50L
    }
}
