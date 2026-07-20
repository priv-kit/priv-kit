package priv.kit.ui.adb

import priv.kit.core.adb.PrivilegeAdbAuthorizationCheckResult
import priv.kit.core.adb.PrivilegeAdbAuthorizationStatus
import priv.kit.core.adb.PrivilegeAdbPairingCheckResult
import priv.kit.core.adb.PrivilegeAdbPairingCheckSession
import priv.kit.core.adb.PrivilegeAdbStarter
import priv.kit.core.adb.PrivilegeAdbTcpAuthorizationCheckSession
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUiAdbConnectionSessions {
    private val wirelessPairingCheckSessionLock = Any()
    private var wirelessPairingCheckSession: PrivilegeAdbPairingCheckSession? = null
    private val tcpAuthorizationCheckSessionLock = Any()
    private var tcpAuthorizationCheckSession: PrivilegeAdbTcpAuthorizationCheckSession? = null
    private var tcpAuthorizationCheckSessionPort: Int? = null

    fun checkTcpAuthorization(
        starter: PrivilegeAdbStarter,
        tcpPort: Int,
        stop: AtomicBoolean,
    ): PrivilegeAdbAuthorizationCheckResult? {
        if (stop.get()) return null
        currentTcpAuthorizationCheckSession(tcpPort)?.let { session ->
            val result = session.check()
            if (stop.get()) {
                clearTcpAuthorizationCheckSession(session)
                return null
            }
            if (result.status == PrivilegeAdbAuthorizationStatus.AUTHORIZED) return result
            clearTcpAuthorizationCheckSession(session)
            return result
        }

        val session = runCatching {
            starter.openTcpAuthorizationCheckSession(tcpPort = tcpPort)
        }.getOrElse {
            if (stop.get()) return null
            return starter.checkTcpAuthorization(tcpPort = tcpPort)
        }
        replaceTcpAuthorizationCheckSession(session, tcpPort)
        if (stop.get()) {
            clearTcpAuthorizationCheckSession(session)
            return null
        }
        val result = session.check()
        if (result.status != PrivilegeAdbAuthorizationStatus.AUTHORIZED || stop.get()) {
            clearTcpAuthorizationCheckSession(session)
        }
        return result
    }

    fun checkWirelessAdbPairing(
        starter: PrivilegeAdbStarter,
        connectPort: Int?,
        timeoutMillis: Long,
        stop: AtomicBoolean,
    ): PrivilegeAdbPairingCheckResult? {
        if (connectPort == null) {
            closeWirelessPairingCheckSession()
            return null
        }
        currentWirelessPairingCheckSession()?.let { session ->
            val result = session.check()
            if (result.paired) return result
            clearWirelessPairingCheckSession(session)
            if (stop.get()) return result
        }
        if (stop.get()) return null

        val session = starter.openPairingCheckSession(
            port = connectPort,
            discoverPort = false,
            portDiscoveryTimeoutMillis = timeoutMillis,
        )
        replaceWirelessPairingCheckSession(session)
        if (stop.get()) {
            clearWirelessPairingCheckSession(session)
            return null
        }
        val result = session.check()
        if (!result.paired || stop.get()) {
            clearWirelessPairingCheckSession(session)
        }
        return result
    }

    fun closeTcpAuthorizationCheckSession() {
        val sessionToClose = synchronized(tcpAuthorizationCheckSessionLock) {
            val session = tcpAuthorizationCheckSession
            tcpAuthorizationCheckSession = null
            tcpAuthorizationCheckSessionPort = null
            session
        }
        sessionToClose?.close()
    }

    fun closeWirelessPairingCheckSession() {
        val sessionToClose = synchronized(wirelessPairingCheckSessionLock) {
            val session = wirelessPairingCheckSession
            wirelessPairingCheckSession = null
            session
        }
        sessionToClose?.close()
    }

    private fun currentTcpAuthorizationCheckSession(
        tcpPort: Int,
    ): PrivilegeAdbTcpAuthorizationCheckSession? {
        var currentSession: PrivilegeAdbTcpAuthorizationCheckSession? = null
        val staleSession = synchronized(tcpAuthorizationCheckSessionLock) {
            if (tcpAuthorizationCheckSessionPort == tcpPort) {
                currentSession = tcpAuthorizationCheckSession
                null
            } else {
                val stale = tcpAuthorizationCheckSession
                tcpAuthorizationCheckSession = null
                tcpAuthorizationCheckSessionPort = null
                stale
            }
        }
        currentSession?.let { return it }
        staleSession?.close()
        return null
    }

    private fun replaceTcpAuthorizationCheckSession(
        session: PrivilegeAdbTcpAuthorizationCheckSession,
        tcpPort: Int,
    ) {
        val previousSession = synchronized(tcpAuthorizationCheckSessionLock) {
            val previous = tcpAuthorizationCheckSession
            tcpAuthorizationCheckSession = session
            tcpAuthorizationCheckSessionPort = tcpPort
            previous
        }
        previousSession?.takeIf { it !== session }?.close()
    }

    private fun clearTcpAuthorizationCheckSession(session: PrivilegeAdbTcpAuthorizationCheckSession) {
        val sessionToClose = synchronized(tcpAuthorizationCheckSessionLock) {
            if (tcpAuthorizationCheckSession === session) {
                tcpAuthorizationCheckSession = null
                tcpAuthorizationCheckSessionPort = null
                session
            } else {
                null
            }
        }
        sessionToClose?.close()
    }

    private fun currentWirelessPairingCheckSession(): PrivilegeAdbPairingCheckSession? =
        synchronized(wirelessPairingCheckSessionLock) {
            wirelessPairingCheckSession
        }

    private fun replaceWirelessPairingCheckSession(session: PrivilegeAdbPairingCheckSession) {
        val previousSession = synchronized(wirelessPairingCheckSessionLock) {
            val previous = wirelessPairingCheckSession
            wirelessPairingCheckSession = session
            previous
        }
        previousSession?.takeIf { it !== session }?.close()
    }

    private fun clearWirelessPairingCheckSession(session: PrivilegeAdbPairingCheckSession) {
        val sessionToClose = synchronized(wirelessPairingCheckSessionLock) {
            if (wirelessPairingCheckSession === session) {
                wirelessPairingCheckSession = null
                session
            } else {
                null
            }
        }
        sessionToClose?.close()
    }
}
