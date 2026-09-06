package priv.kit.core.internal.server

internal enum class PrivilegeOwnerReconnectMode {
    PASSIVE,
    ACTIVE,
}

internal data class PrivilegeOwnerReconnectPhase(
    val mode: PrivilegeOwnerReconnectMode,
    val deadlineMillis: Long,
)

internal fun planOwnerReconnectPhases(
    startedAtMillis: Long,
    followDeathDelayMillis: Long,
    activeReconnect: Boolean,
    plannedPassiveReconnectTimeoutMillis: Long?,
): List<PrivilegeOwnerReconnectPhase> {
    require(startedAtMillis >= 0L) { "startedAtMillis must not be negative" }
    require(followDeathDelayMillis > 0L) { "followDeathDelayMillis must be positive" }
    require(
        plannedPassiveReconnectTimeoutMillis == null ||
            plannedPassiveReconnectTimeoutMillis > 0L,
    ) {
        "plannedPassiveReconnectTimeoutMillis must be positive"
    }

    val reconnectDeadlineMillis = ownerReconnectDeadline(
        startMillis = startedAtMillis,
        durationMillis = followDeathDelayMillis,
    )
    if (!activeReconnect) {
        return listOf(
            PrivilegeOwnerReconnectPhase(
                mode = PrivilegeOwnerReconnectMode.PASSIVE,
                deadlineMillis = reconnectDeadlineMillis,
            ),
        )
    }

    val passiveTimeoutMillis = plannedPassiveReconnectTimeoutMillis
        ?: return listOf(
            PrivilegeOwnerReconnectPhase(
                mode = PrivilegeOwnerReconnectMode.ACTIVE,
                deadlineMillis = reconnectDeadlineMillis,
            ),
        )
    val passiveDeadlineMillis = minOf(
        reconnectDeadlineMillis,
        ownerReconnectDeadline(
            startMillis = startedAtMillis,
            durationMillis = passiveTimeoutMillis,
        ),
    )
    return buildList {
        add(
            PrivilegeOwnerReconnectPhase(
                mode = PrivilegeOwnerReconnectMode.PASSIVE,
                deadlineMillis = passiveDeadlineMillis,
            ),
        )
        if (passiveDeadlineMillis < reconnectDeadlineMillis) {
            add(
                PrivilegeOwnerReconnectPhase(
                    mode = PrivilegeOwnerReconnectMode.ACTIVE,
                    deadlineMillis = reconnectDeadlineMillis,
                ),
            )
        }
    }
}

internal fun ownerReconnectDeadline(
    startMillis: Long,
    durationMillis: Long,
): Long = if (durationMillis >= Long.MAX_VALUE - startMillis) {
    Long.MAX_VALUE
} else {
    startMillis + durationMillis
}

internal fun isOwnerReconnectCandidatePid(
    processPid: Int,
    serverPid: Int,
    excludedOwnerPid: Int?,
): Boolean =
    processPid != serverPid &&
        processPid != excludedOwnerPid
