package priv.kit.core.internal.server

import android.os.Bundle
import android.os.IBinder
import android.os.Binder
import priv.kit.core.internal.runtime.PrivilegeNativeStarterResolver
import priv.kit.core.internal.userservice.PrivilegeUserServiceLoader
import priv.kit.core.internal.runtime.PrivilegeCrashRecorder
import android.os.SystemClock
import priv.kit.core.internal.core.PrivilegeContentProviderCall
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import android.os.Process as AndroidProcess
import priv.kit.core.internal.userservice.IPrivilegeUserServiceProcess
import priv.kit.core.internal.userservice.PrivilegeUserServiceContract
import priv.kit.core.internal.userservice.PrivilegeUserServiceHost
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import priv.kit.core.userservice.PrivilegeUserServiceProcess
import java.io.File

internal class PrivilegeServerUserServiceHost(
    private val config: PrivilegeServerConfig,
    private val serverLifecycle: IBinder = Binder(),
    private val processClaimer: PrivilegeServerUserServiceProcessClaimer =
        PrivilegeServerUserServiceProcessClaimer(),
    private val processStarter: (PrivilegeServerUserServiceProcessStartCommand) -> java.lang.Process =
        PrivilegeServerUserServiceHost::startProcess,
) : PrivilegeUserServiceHost {
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
            currentUid = AndroidProcess.myUid(),
            uidLauncher = {
                val context = PrivilegeUserServiceLoader.createPackageContext(config.packageName, config.userId)
                PrivilegeNativeStarterResolver.commandArguments(
                    PrivilegeNativeStarterResolver.resolve(context.applicationInfo),
                )
            },
            crashLogDirectories = PrivilegeCrashRecorder.currentDirectories,
        )
        return processStarter(command)
    }

    override fun awaitDedicatedProcess(
        token: String,
        handle: Process,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess {
        return processClaimer.await(
            config = config,
            token = token,
            timeoutMillis = timeoutMillis,
            checkProcess = {
                if (!handle.isAlive) {
                    val output = handle.inputStream.bufferedReader().readText().trim()
                    error("UserService exited with code ${handle.exitValue()}: $output")
                }
            },
        ).also { it.attachServer(serverLifecycle) }
    }

    override fun killDedicatedProcess(process: Process) {
        PrivilegeServerUserServiceProcessKiller.kill(process)
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
)

internal object PrivilegeServerUserServiceProcessCommand {
    fun build(
        config: PrivilegeServerConfig,
        spec: PrivilegeUserServiceSpec,
        token: String,
        currentUid: Int,
        crashLogDirectories: List<File> = emptyList(),
        uidLauncher: () -> List<String> = { error("UID launcher was not supplied") },
    ): PrivilegeServerUserServiceProcessStartCommand {
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(config.packageName)
        val processName = buildProcessName(config.packageName, spec)
        require(!spec.process.isEmbedded) { "Embedded services do not have a dedicated process" }
        val targetUid = if (spec.process == PrivilegeUserServiceProcess.INHERIT) currentUid else spec.process.uid
        val launcher = if (targetUid == currentUid) emptyList() else uidLauncher() + listOf("--user-service", targetUid.toString())
        return PrivilegeServerUserServiceProcessStartCommand(
            arguments = launcher + listOf(
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
            ) + (spec.serviceClassName?.let { listOf("--service-class", it) } ?: emptyList()),
            environment = mapOf(
                "CLASSPATH" to config.classpath,
                // Override any stale list inherited from the original server launch.
                PrivilegeHandshakeContract.ENV_CRASH_LOG_DIRECTORIES to
                    PrivilegeCrashRecorder.encodeDirectories(crashLogDirectories),
            ),
        )
    }

    private fun buildProcessName(
        packageName: String,
        spec: PrivilegeUserServiceSpec,
    ): String {
        val suffix = listOfNotNull(spec.serviceClassName?.substringAfterLast('.') ?: "binder", spec.tag)
            .joinToString("-")
            .map { char ->
                if (char.isLetterOrDigit() || char == '_' || char == '-') char else '-'
            }
            .joinToString("")
            .take(48)
            .ifBlank { "user-service" }
        return "$packageName:$suffix"
    }

    private const val USER_SERVICE_MAIN_CLASS = "priv.kit.core.internal.userservice.PrivilegeUserServiceMain"
}

internal class PrivilegeServerUserServiceProcessClaimer(
    private val providerCall: (PrivilegeServerConfig, String) -> Bundle? =
        PrivilegeServerUserServiceProcessClaimer::claimProcess,
) {
    fun await(
        config: PrivilegeServerConfig,
        token: String,
        timeoutMillis: Long,
        checkProcess: () -> Unit = {},
    ): IPrivilegeUserServiceProcess {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            checkProcess()
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
            }
            Thread.sleep(CLAIM_RETRY_DELAY_MILLIS)
        }
        throw IllegalStateException(
            "Timed out waiting for dedicated UserService process",
            lastFailure,
        )
    }

    private companion object {
        const val CLAIM_RETRY_DELAY_MILLIS = 50L
        const val TAG = "PrivKitServer"

        fun claimProcess(
            config: PrivilegeServerConfig,
            token: String,
        ): Bundle? =
            PrivilegeContentProviderCall.call(
                authority = PrivilegeHandshakeContract.providerAuthority(config.packageName),
                method = PrivilegeUserServiceContract.METHOD_USER_SERVICE_CLAIM,
                arg = token,
                extras = Bundle().apply {
                    putString(PrivilegeUserServiceContract.EXTRA_TOKEN, token)
                },
                userId = config.userId,
                logTag = TAG,
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
