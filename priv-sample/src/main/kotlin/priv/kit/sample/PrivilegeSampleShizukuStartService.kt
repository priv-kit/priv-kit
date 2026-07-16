package priv.kit.sample

import android.content.Context
import androidx.annotation.Keep
import priv.kit.PrivilegeStartupLogListener
import priv.kit.PrivilegeExternalStartup
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

internal class PrivilegeSampleShizukuStartService @Keep constructor() :
    IPrivilegeSampleShizukuStartService.Stub() {
    private val startRunning = AtomicBoolean(false)

    @Keep
    constructor(context: Context) : this()

    override fun startWithCallback(
        commandLine: String,
        callback: IPrivilegeSampleShizukuStartCallback?,
    ) {
        if (!startRunning.compareAndSet(false, true)) {
            callback.notifyFailure(
                IllegalStateException("Shizuku start command is already running"),
            )
            return
        }
        thread(name = "priv-kit-shizuku-start", isDaemon = true) {
            try {
                val result = PrivilegeExternalStartup.runInCurrentProcess(
                    commandLine = commandLine,
                    startupLogListener = PrivilegeStartupLogListener { line ->
                        runCatching {
                            callback?.onOutput(line.source, line.message)
                        }
                    },
                )
                runCatching {
                    callback?.onFinished(result.output)
                }
            } catch (throwable: Throwable) {
                callback.notifyFailure(throwable)
            } finally {
                startRunning.set(false)
            }
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun IPrivilegeSampleShizukuStartCallback?.notifyFailure(throwable: Throwable) {
        runCatching {
            this?.onFailure(
                throwable.message ?: throwable.javaClass.name,
                throwable.toDiagnosticString(),
            )
        }
    }
}
