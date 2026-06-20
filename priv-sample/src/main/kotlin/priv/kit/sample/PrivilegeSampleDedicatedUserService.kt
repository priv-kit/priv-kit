package priv.kit.sample

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import kotlin.system.exitProcess

internal class PrivilegeSampleDedicatedUserService private constructor(
    private val packageName: String,
) : IPrivilegeSampleDedicatedUserService.Stub() {
    private var mode: String = "created"
    private var callCount: Int = 0

    @Keep
    constructor() : this(packageName = "no-context")

    @Keep
    constructor(context: Context) : this(packageName = context.packageName)

    override fun destroy() {
        mode = "destroyed"
        exitProcess(0)
    }

    override fun describe(label: String?): String {
        callCount += 1
        return buildString {
            append(label?.ifBlank { "dedicated" } ?: "dedicated")
            append(": mode=")
            append(mode)
            append(", uid=")
            append(Process.myUid())
            append(", pid=")
            append(Process.myPid())
            append(", package=")
            append(packageName)
            append(", calls=")
            append(callCount)
        }
    }

    override fun getUid(): Int = Process.myUid()

    override fun getPid(): Int = Process.myPid()

    override fun getCallCount(): Int = callCount

    override fun getMode(): String = mode
}
