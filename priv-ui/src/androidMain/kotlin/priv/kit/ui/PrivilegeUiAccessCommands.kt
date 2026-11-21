package priv.kit.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** VM-owned platform requests. No Activity, launcher, or composition is retained. */
internal class PrivilegeUiAccessCommands {
    internal sealed class Command<T>(val key: String) {
        val result = CompletableDeferred<T>()
        var launched = false
        class Permission(key: String, val permission: String, val checkOnly: Boolean) :
            Command<PrivilegeUiPermissionState>(key)
        class Settings(key: String, val destination: PrivilegeUiSettingsDestination) : Command<Boolean>(key)
    }

    private val mutex = Mutex()
    private val ownerKey = UUID.randomUUID().toString()
    private var serial = 0L
    private val pendingState = MutableStateFlow<Command<*>?>(null)
    val pending = pendingState.asStateFlow()

    suspend fun permission(permission: String, checkOnly: Boolean = false): PrivilegeUiPermissionState =
        awaitCommand { Command.Permission(it, permission, checkOnly) }

    suspend fun openSettings(destination: PrivilegeUiSettingsDestination): Boolean =
        awaitCommand { Command.Settings(it, destination) }

    fun takeForLaunch(command: Command<*>): Boolean = synchronized(this) {
        if (pendingState.value !== command || command.launched || command.result.isCompleted) false
        else { command.launched = true; true }
    }

    fun cancelPending() { pendingState.value?.result?.cancel() }

    private suspend fun <T> awaitCommand(create: (String) -> Command<T>): T = mutex.withLock {
        val command = create("priv-ui-access-$ownerKey-${++serial}")
        pendingState.value = command
        try {
            command.result.await()
        } finally {
            command.result.cancel()
            if (pendingState.value === command) pendingState.value = null
        }
    }
}
