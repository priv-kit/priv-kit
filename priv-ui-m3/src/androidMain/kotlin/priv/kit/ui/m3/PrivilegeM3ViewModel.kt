package priv.kit.ui.m3

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import priv.kit.ui.*
import priv.kit.ui.m3.resources.*
import org.jetbrains.compose.resources.getString

/** One page ViewModel containing business state and Material 3 presentation state. */
@ExperimentalPrivilegeUiApi
public open class PrivilegeM3ViewModel @JvmOverloads public constructor(
    application: Application,
    config: PrivilegeUiConfig = PrivilegeUiConfig(),
    initialM3State: PrivilegeM3State = PrivilegeM3State(),
) : PrivilegeUiViewModel(application, config) {
    private val presentation = PrivilegeM3Controller(viewModelScope, initialM3State)

    public val m3State: PrivilegeM3State get() = presentation.state
    public val m3Actions: PrivilegeM3Actions get() = presentation.actions

    protected override val accessHandler: PrivilegeUiAccessHandler = PrivilegeUiAccessHandler { request ->
        when (request) {
            PrivilegeUiAccessRequest.BatteryOptimization -> runPrivilegeM3BatteryOptimizationFlow(
                restricted = { isPrivilegeUiBatteryOptimizationRestricted(getApplication()) },
                explain = { explainAccess(PrivilegeM3AccessExplanation.BATTERY_OPTIMIZATION) },
                request = { requestPlatformAccess(request) },
            )
            is PrivilegeUiAccessRequest.Permission -> {
                val explanation = when (request) {
                    PrivilegeUiAccessRequest.Permission.Notifications -> PrivilegeM3AccessExplanation.NOTIFICATIONS
                    PrivilegeUiAccessRequest.Permission.LocalNetwork -> PrivilegeM3AccessExplanation.LOCAL_NETWORK
                }
                val destination = when (request) {
                    PrivilegeUiAccessRequest.Permission.Notifications -> PrivilegeUiSettingsDestination.NOTIFICATIONS
                    PrivilegeUiAccessRequest.Permission.LocalNetwork -> PrivilegeUiSettingsDestination.APPLICATION_PERMISSIONS
                }
                runPrivilegeM3PermissionFlow(
                    request = { requestPlatformAccess(request) },
                    requiresSettings = { requiresAccessSettings(request) },
                    granted = { isAccessGranted(request) },
                    explain = { explainAccess(explanation) },
                    openSettings = {
                        check(openAccessSettings(destination)) { getString(Res.string.priv_ui_settings_unavailable) }
                    },
                )
            }
        }
    }

    init {
        addCloseable { presentation.close() }
        viewModelScope.launch {
            authorization.collect(presentation::updateAuthorization)
        }
    }

    protected suspend fun explainAccess(explanation: PrivilegeM3AccessExplanation): Boolean =
        presentation.explainAccess(explanation)

    public fun updateHostVisibility(resumed: Boolean, focused: Boolean) {
        presentation.updateHostVisibility(resumed, focused)
    }
}
