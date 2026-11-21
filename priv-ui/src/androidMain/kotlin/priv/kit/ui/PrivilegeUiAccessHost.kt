package priv.kit.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Activity bridge: registers callbacks and launches commands; never owns the access flow. */
@ExperimentalPrivilegeUiApi
public class PrivilegeUiAccessHost internal constructor(
    private val activity: ComponentActivity,
    private val commands: PrivilegeUiAccessCommands,
    private val registry: ActivityResultRegistry = activity.activityResultRegistry,
) : DefaultLifecycleObserver, AutoCloseable {
    public constructor(activity: ComponentActivity, viewModel: PrivilegeUiViewModel) :
        this(activity, viewModel.accessCommands)

    private val binding = activity.lifecycleScope.launch {
        commands.pending.collectLatest { command ->
            when (command) {
                is PrivilegeUiAccessCommands.Command.Permission -> bindPermission(command)
                is PrivilegeUiAccessCommands.Command.Settings -> bindSettings(command)
                null -> Unit
            }
        }
    }

    init { activity.lifecycle.addObserver(this) }

    override fun onDestroy(owner: LifecycleOwner) { close() }

    override fun close() {
        binding.cancel()
        activity.lifecycle.removeObserver(this)
    }

    private suspend fun bindPermission(command: PrivilegeUiAccessCommands.Command.Permission) {
        val launcher = registry.register(command.key, ActivityResultContracts.RequestPermission()) {
            command.result.complete(privilegeUiPermissionState(activity, command.permission))
        }
        try {
            if (commands.takeForLaunch(command)) {
                val state = privilegeUiPermissionState(activity, command.permission)
                if (command.checkOnly || state != PrivilegeUiPermissionState.NotGranted.Denied) {
                    command.result.complete(state)
                } else {
                    launcher.launch(command.permission)
                    markPrivilegeUiPermissionRequested(command.permission)
                }
            }
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            command.result.completeExceptionally(error)
        } finally { launcher.unregister() }
    }

    private suspend fun bindSettings(command: PrivilegeUiAccessCommands.Command.Settings) {
        val launcher = registry.register(command.key, ActivityResultContracts.StartActivityForResult()) {
            command.result.complete(true)
        }
        try {
            if (commands.takeForLaunch(command)) {
                val packageUri = Uri.fromParts("package", activity.packageName, null)
                val intents = when (command.destination) {
                    PrivilegeUiSettingsDestination.NOTIFICATIONS -> listOf(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                    PrivilegeUiSettingsDestination.APPLICATION_PERMISSIONS -> listOf(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
                    PrivilegeUiSettingsDestination.BATTERY_OPTIMIZATION -> activity.privilegeUiBatteryOptimizationIntents()
                }
                if (!launchPrivilegeUiSettings(intents) { launcher.launch(it) }) command.result.complete(false)
            }
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            command.result.completeExceptionally(error)
        } finally { launcher.unregister() }
    }
}
