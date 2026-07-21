package priv.kit.sample.userservice

import android.os.Process

internal class PrivilegeSampleUserServiceState(
    private val packageName: String,
    private val defaultLabel: String,
    initialMode: String,
) {
    private var mode: String = initialMode
    private var callCount: Int = 0

    fun markDestroyed() {
        mode = "destroyed"
    }

    fun describe(label: String?): String {
        callCount += 1
        return buildString {
            append(label?.ifBlank { defaultLabel } ?: defaultLabel)
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

    fun getUid(): Int = Process.myUid()

    fun getPid(): Int = Process.myPid()

    fun getCallCount(): Int = callCount

    fun getMode(): String = mode
}
