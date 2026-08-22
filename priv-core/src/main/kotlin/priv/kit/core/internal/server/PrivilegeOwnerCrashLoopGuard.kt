package priv.kit.core.internal.server

import java.util.ArrayDeque

internal class PrivilegeOwnerCrashLoopGuard(
    private val windowMillis: Long,
    private val deathThreshold: Int,
) {
    private val deathTimestamps = ArrayDeque<Long>()
    private var ownerLinkedAtMillis: Long? = null
    private var circuitOpen = false

    init {
        require(windowMillis > 0L) { "windowMillis must be positive" }
        require(deathThreshold > 1) { "deathThreshold must be greater than one" }
    }

    fun onOwnerLinked(elapsedRealtimeMillis: Long) {
        ownerLinkedAtMillis = elapsedRealtimeMillis
    }

    fun onOwnerDied(elapsedRealtimeMillis: Long): PrivilegeOwnerCrashLoopDecision {
        val wasOpen = circuitOpen
        val stableOwnerSession = ownerLinkedAtMillis?.let { linkedAtMillis ->
            elapsedRealtimeMillis - linkedAtMillis >= windowMillis
        } == true
        ownerLinkedAtMillis = null
        if (stableOwnerSession) {
            deathTimestamps.clear()
            circuitOpen = false
        }

        while (
            deathTimestamps.isNotEmpty() &&
            elapsedRealtimeMillis - deathTimestamps.first >= windowMillis
        ) {
            deathTimestamps.removeFirst()
        }
        deathTimestamps.addLast(elapsedRealtimeMillis)
        if (deathTimestamps.size >= deathThreshold) {
            circuitOpen = true
        }

        return PrivilegeOwnerCrashLoopDecision(
            circuitOpen = circuitOpen,
            circuitOpened = !wasOpen && circuitOpen,
            circuitReset = wasOpen && stableOwnerSession,
        )
    }
}

internal data class PrivilegeOwnerCrashLoopDecision(
    val circuitOpen: Boolean,
    val circuitOpened: Boolean,
    val circuitReset: Boolean,
) {
    companion object {
        val CLOSED = PrivilegeOwnerCrashLoopDecision(
            circuitOpen = false,
            circuitOpened = false,
            circuitReset = false,
        )
    }
}
