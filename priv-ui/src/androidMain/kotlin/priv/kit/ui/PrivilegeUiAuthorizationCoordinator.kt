package priv.kit.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the lifetime of an authorization operation, independent of any rendering host. */
internal class PrivilegeUiAuthorizationCoordinator : AutoCloseable {
    private val current = MutableStateFlow<PrivilegeUiAuthorization?>(null)
    val authorization = current.asStateFlow()

    suspend fun <T> withAuthorization(authorization: PrivilegeUiAuthorization, action: suspend () -> T): T {
        current.value = authorization
        return try { action() } finally { current.value = null }
    }

    override fun close() { current.value = null }
}

internal fun privilegeUiTcpAuthorization() = PrivilegeUiAuthorization(PrivilegeUiAuthorizationKind.TCP)
internal fun privilegeUiWirelessDebuggingAuthorization() = PrivilegeUiAuthorization(PrivilegeUiAuthorizationKind.WIRELESS_DEBUGGING)
internal fun privilegeUiRootAuthorization() = PrivilegeUiAuthorization(PrivilegeUiAuthorizationKind.ROOT)
internal fun privilegeUiExternalAuthorization(providerLabel: CharSequence) =
    PrivilegeUiAuthorization(PrivilegeUiAuthorizationKind.EXTERNAL, providerLabel.toString())
