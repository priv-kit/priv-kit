package priv.kit.sample

import android.content.Context
import android.os.Process
import androidx.annotation.Keep

internal class PrivilegeSampleEmbeddedUserService private constructor(
    private val packageName: String,
) : IPrivilegeSampleEmbeddedUserService.Stub() {
    private var callCount: Int = 0

    @Keep
    constructor() : this(packageName = "no-context")

    @Keep
    constructor(context: Context) : this(packageName = context.packageName)

    override fun describe(label: String?): String {
        callCount += 1
        return buildString {
            append(label?.ifBlank { "embedded" } ?: "embedded")
            append(": mode=embedded")
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

    override fun getMode(): String = "embedded"
}
