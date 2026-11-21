package priv.kit.ui

/** Fixed access requests supported by the startup page. */
@ExperimentalPrivilegeUiApi
public sealed interface PrivilegeUiAccessRequest {
    public sealed interface Permission : PrivilegeUiAccessRequest {
        public val permission: String

        public data object Notifications : Permission {
            override val permission: String = android.Manifest.permission.POST_NOTIFICATIONS
        }

        public data object LocalNetwork : Permission {
            override val permission: String = android.Manifest.permission.ACCESS_LOCAL_NETWORK
        }
    }

    public data object BatteryOptimization : PrivilegeUiAccessRequest
}

/**
 * VM-owned flow called on the main thread from the page ViewModel scope.
 * True means granted/exempt, false means not granted. Do not capture an Activity or Composition.
 * Propagate coroutine cancellation to abort the business operation rather than report denial.
 */
@ExperimentalPrivilegeUiApi
public fun interface PrivilegeUiAccessHandler {
    public suspend fun request(request: PrivilegeUiAccessRequest): Boolean
}
