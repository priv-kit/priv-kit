package priv.kit.core.internal.server

import android.os.IBinder

internal class PrivilegeOwnerRestartPlanner(
    private val armingWindowMillis: Long,
    private val elapsedRealtime: () -> Long,
) {
    private var plan: Plan? = null

    init {
        require(armingWindowMillis > 0L) { "armingWindowMillis must be positive" }
    }

    fun prepare(
        ownerBinder: IBinder?,
        ownerPid: Int,
        passiveReconnectTimeoutMillis: Long,
    ) {
        require(ownerPid > 0) { "ownerPid must be positive" }
        require(passiveReconnectTimeoutMillis > 0L) {
            "passiveReconnectTimeoutMillis must be positive"
        }
        plan = Plan(
            ownerBinder = ownerBinder,
            ownerPid = ownerPid,
            armedUntilMillis = ownerReconnectDeadline(
                startMillis = elapsedRealtime(),
                durationMillis = armingWindowMillis,
            ),
            passiveReconnectTimeoutMillis = passiveReconnectTimeoutMillis,
        )
    }

    fun bindOwner(ownerBinder: IBinder) {
        val nowMillis = elapsedRealtime()
        plan = plan?.let { current ->
            when {
                nowMillis > current.armedUntilMillis -> null
                current.ownerBinder == null -> current.copy(ownerBinder = ownerBinder)
                current.ownerBinder === ownerBinder -> current
                else -> null
            }
        }
    }

    fun consume(deadOwnerBinder: IBinder): PrivilegePlannedOwnerRestart? {
        val current = plan.also { plan = null } ?: return null
        return PrivilegePlannedOwnerRestart(
            ownerPid = current.ownerPid,
            passiveReconnectTimeoutMillis = current.passiveReconnectTimeoutMillis,
        ).takeIf {
            current.ownerBinder === deadOwnerBinder &&
                elapsedRealtime() <= current.armedUntilMillis
        }
    }

    private data class Plan(
        val ownerBinder: IBinder?,
        val ownerPid: Int,
        val armedUntilMillis: Long,
        val passiveReconnectTimeoutMillis: Long,
    )
}

internal data class PrivilegePlannedOwnerRestart(
    val ownerPid: Int,
    val passiveReconnectTimeoutMillis: Long,
)
