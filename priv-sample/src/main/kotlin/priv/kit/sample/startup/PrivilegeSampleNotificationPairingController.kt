package priv.kit.sample.startup

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import priv.kit.ui.PrivilegeAdbPairingService
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationEvent

internal class PrivilegeSampleNotificationPairingController(
    private val activity: ComponentActivity,
    private val ownerId: () -> String,
    private val onPairingCodeSubmitted: (String) -> Unit,
    private val onStopped: () -> Unit,
    private val onUnavailable: (String) -> Unit,
    private val onDetached: () -> Unit,
    private val onPermissionResult: (Boolean) -> Unit,
) {
    private val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
        onPermissionResult,
    )
    private var observationJob: Job? = null

    fun startObserving() {
        if (observationJob != null) return
        observationJob = activity.lifecycleScope.launch {
            PrivilegeAdbPairingService.notificationEvents.collect { event ->
                if (event.ownerId != ownerId()) return@collect
                when (event) {
                    is PrivilegeAdbPairingNotificationEvent.Submit -> {
                        stopPrivilegeSampleNotificationPairing(activity, ownerId())
                        onPairingCodeSubmitted(event.pairingCode)
                    }
                    is PrivilegeAdbPairingNotificationEvent.Stop -> onStopped()
                    is PrivilegeAdbPairingNotificationEvent.Unavailable -> onUnavailable(event.message)
                    is PrivilegeAdbPairingNotificationEvent.Detached -> onDetached()
                }
            }
        }
    }

    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onPermissionResult(true)
        }
    }

    fun dispose() {
        observationJob?.cancel()
        observationJob = null
    }
}
