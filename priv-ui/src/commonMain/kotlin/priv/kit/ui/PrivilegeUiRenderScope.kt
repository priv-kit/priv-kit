package priv.kit.ui

/** A presentation snapshot. The host owns effects; the renderer owns all visual surfaces. */
@ExperimentalPrivilegeUiApi
public class PrivilegeUiRenderScope(
    public val state: PrivilegeUiScreenState,
    public val actions: PrivilegeUiActions,
    public val interactionEnabled: Boolean,
    public val authorization: PrivilegeUiAuthorization?,
    public val onBack: () -> Unit,
    public val showMessage: (String) -> Unit,
)
