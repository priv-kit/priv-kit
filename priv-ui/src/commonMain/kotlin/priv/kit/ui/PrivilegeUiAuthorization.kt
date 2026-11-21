package priv.kit.ui

@ExperimentalPrivilegeUiApi
public enum class PrivilegeUiAuthorizationKind { ROOT, WIRELESS_DEBUGGING, TCP, EXTERNAL }

@ExperimentalPrivilegeUiApi
public data class PrivilegeUiAuthorization(
    public val kind: PrivilegeUiAuthorizationKind,
    public val providerLabel: String? = null,
)
