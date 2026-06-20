package priv.kit.sample

import android.os.Process

internal class PrivilegeSampleEmbeddedUserService : IPrivilegeSampleEmbeddedUserService.Stub() {
    private var callCount: Int = 0

    override fun describe(label: String?): String {
        callCount += 1
        return buildString {
            append(label?.ifBlank { "embedded" } ?: "embedded")
            append(": mode=embedded")
            append(", uid=")
            append(Process.myUid())
            append(", pid=")
            append(Process.myPid())
            append(", calls=")
            append(callCount)
        }
    }

    override fun getUid(): Int = Process.myUid()

    override fun getPid(): Int = Process.myPid()

    override fun getCallCount(): Int = callCount

    override fun getMode(): String = "embedded"
}
