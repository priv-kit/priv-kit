package priv.kit.sample.userservice

import android.content.Context
import androidx.annotation.Keep

internal class PrivilegeSampleDedicatedUserService private constructor(
    private val state: PrivilegeSampleUserServiceState,
) : IPrivilegeSampleDedicatedUserService.Stub() {
    @Keep
    constructor() : this(createState(packageName = "no-context"))

    @Keep
    constructor(context: Context) : this(createState(packageName = context.packageName))

    override fun destroy() {
        state.markDestroyed()
    }

    override fun describe(label: String?): String = state.describe(label)

    override fun getUid(): Int = state.getUid()

    override fun getPid(): Int = state.getPid()

    override fun getCallCount(): Int = state.getCallCount()

    override fun getMode(): String = state.getMode()

    companion object {
        private fun createState(packageName: String): PrivilegeSampleUserServiceState =
            PrivilegeSampleUserServiceState(
                packageName = packageName,
                defaultLabel = "dedicated",
                initialMode = "created",
            )
    }
}
