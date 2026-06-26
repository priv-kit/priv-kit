package priv.kit.server

import android.os.Bundle
import android.os.SystemClock
import priv.kit.core.PrivilegeHandshakeContract
import android.os.Process as AndroidProcess
import priv.kit.userservice.IPrivilegeUserServiceProcess
import priv.kit.userservice.PrivilegeUserServiceContract
import priv.kit.userservice.PrivilegeUserServiceHost
import priv.kit.userservice.PrivilegeUserServiceSpec
import priv.kit.userservice.PrivilegeUserServiceStartException
import java.util.concurrent.TimeUnit

internal class PrivilegeServerUserServiceHost(
    private val config: PrivilegeServerConfig,
    private val processClaimer: PrivilegeServerUserServiceProcessClaimer =
        PrivilegeServerUserServiceProcessClaimer(),
    private val processStarter: (PrivilegeServerUserServiceProcessStartCommand) -> java.lang.Process =
        PrivilegeServerUserServiceHost::startProcess,
) : PrivilegeUserServiceHost {
    override val uid: Int
        get() = AndroidProcess.myUid()

    override val pid: Int
        get() = AndroidProcess.myPid()

    override val packageName: String
        get() = config.packageName

    override val userId: Int
        get() = config.userId

    override fun startDedicatedProcess(
        spec: PrivilegeUserServiceSpec,
        token: String,
    ): Process {
        val command = PrivilegeServerUserServiceProcessCommand.build(
            config = config,
            spec = spec,
            token = token,
            serverPid = AndroidProcess.myPid(),
        )
        return processStarter(command)
    }

    override fun awaitDedicatedProcess(
        token: String,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess =
        processClaimer.await(
            config = config,
            token = token,
            timeoutMillis = timeoutMillis,
        )

    override fun killDedicatedProcess(process: Process) {
        PrivilegeServerUserServiceProcessKiller.kill(process)
    }

    override fun awaitDedicatedProcessExit(
        process: Process,
        timeoutMillis: Long,
    ): Boolean {
        if (timeoutMillis <= 0L) return !process.isAlive
        return try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            !process.isAlive
        }
    }

    private companion object {
        fun startProcess(command: PrivilegeServerUserServiceProcessStartCommand): java.lang.Process =
            ProcessBuilder(command.arguments).apply {
                environment().putAll(command.environment)
                redirectErrorStream(true)
            }.start()
    }
}

internal data class PrivilegeServerUserServiceProcessStartCommand(
    val arguments: List<String>,
    val environment: Map<String, String>,
    val processName: String,
)

internal object PrivilegeServerUserServiceProcessCommand {
    fun build(
        config: PrivilegeServerConfig,
        spec: PrivilegeUserServiceSpec,
        token: String,
        serverPid: Int,
    ): PrivilegeServerUserServiceProcessStartCommand {
        val classpath = config.classpath.ifBlank {
            throw PrivilegeUserServiceStartException("Server classpath is unavailable")
        }
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(config.packageName)
        val processName = buildProcessName(config.packageName, spec)
        return PrivilegeServerUserServiceProcessStartCommand(
            arguments = listOf(
                "/system/bin/app_process",
                "/system/bin",
                "--nice-name=$processName",
                USER_SERVICE_MAIN_CLASS,
                "--token",
                token,
                "--provider-authority",
                providerAuthority,
                "--package-name",
                config.packageName,
                "--user-id",
                config.userId.toString(),
                "--service-class",
                spec.serviceClassName,
                "--server-pid",
                serverPid.toString(),
            ),
            environment = mapOf("CLASSPATH" to classpath),
            processName = processName,
        )
    }

    private fun buildProcessName(
        packageName: String,
        spec: PrivilegeUserServiceSpec,
    ): String {
        val tagSuffix = spec.tag.takeUnless { it == DEDICATED_PROCESS_TAG }
        val suffix = listOfNotNull(spec.serviceClassName.substringAfterLast('.'), tagSuffix)
            .joinToString("-")
            .map { char ->
                if (char.isLetterOrDigit() || char == '_' || char == '-') char else '-'
            }
            .joinToString("")
            .take(48)
            .ifBlank { "user-service" }
        return "$packageName:$suffix"
    }

    private const val USER_SERVICE_MAIN_CLASS = "priv.kit.userservice.PrivilegeUserServiceMain"
    private const val DEDICATED_PROCESS_TAG = "dedicated"
}

internal class PrivilegeServerUserServiceProcessClaimer(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val providerCall: (PrivilegeServerConfig, String) -> Bundle? =
        PrivilegeServerUserServiceProcessClaimer::claimProcess,
) {
    fun await(
        config: PrivilegeServerConfig,
        token: String,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess {
        val deadline = elapsedRealtime() + timeoutMillis
        var lastFailure: Throwable? = null
        while (elapsedRealtime() < deadline) {
            val response = runCatching {
                providerCall(config, token)
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
            sleep(CLAIM_RETRY_DELAY_MILLIS)
        }
        throw PrivilegeUserServiceStartException(
            "Timed out waiting for dedicated UserService process",
            lastFailure,
        )
    }

    private companion object {
        const val CLAIM_RETRY_DELAY_MILLIS = 50L

        fun claimProcess(
            config: PrivilegeServerConfig,
            token: String,
        ): Bundle? =
            PrivilegeServerProviderCall.call(
                authority = PrivilegeHandshakeContract.providerAuthority(config.packageName),
                method = PrivilegeUserServiceContract.METHOD_USER_SERVICE_CLAIM,
                arg = token,
                extras = Bundle().apply {
                    putString(PrivilegeUserServiceContract.EXTRA_TOKEN, token)
                },
                userId = config.userId,
            )
    }
}

internal object PrivilegeServerUserServiceProcessKiller {
    fun kill(process: java.lang.Process) {
        runCatching {
            process.destroyForcibly()
        }.onFailure {
            runCatching {
                process.destroy()
            }
        }
    }
}
