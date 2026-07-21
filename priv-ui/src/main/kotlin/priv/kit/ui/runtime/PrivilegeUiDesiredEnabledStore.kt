package priv.kit.ui.runtime

import android.content.Context
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import priv.kit.core.internal.runtime.PrivilegeRuntimeConnectionOrigin
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.shared.PrivilegeBinaryFileStore
import priv.kit.shared.PrivilegeStoragePaths

internal class PrivilegeUiDesiredEnabledStore(context: Context) {
    private val file = PrivilegeStoragePaths.file(
        context = context,
        fileName = DESIRED_ENABLED_FILE_NAME,
    )

    fun read(): Boolean =
        synchronized(fileLock) {
            PrivilegeBinaryFileStore.readIfExists(file)
                ?.contentEquals(ENABLED_BYTES) == true
        }

    fun write(enabled: Boolean) {
        synchronized(fileLock) {
            PrivilegeBinaryFileStore.writeAtomically(
                file = file,
                bytes = if (enabled) ENABLED_BYTES else DISABLED_BYTES,
            )
        }
    }

    private companion object {
        const val DESIRED_ENABLED_FILE_NAME = "ui-desired-enabled"
        val ENABLED_BYTES = byteArrayOf('1'.code.toByte())
        val DISABLED_BYTES = byteArrayOf('0'.code.toByte())
        val fileLock = Any()
    }
}

internal class PrivilegeUiDesiredEnabledManager(
    context: Context,
    registerServerHandshakeAcceptedListener: (
        ((PrivilegeRuntimeConnectionOrigin) -> Unit) -> Closeable
    ) = PrivilegeRuntimeStartCoordinator::addServerHandshakeAcceptedListener,
) : AutoCloseable {
    private val store = PrivilegeUiDesiredEnabledStore(context.applicationContext)
    private val stateLock = Any()
    private val mutableDesiredEnabled = MutableStateFlow(store.read())
    private val serverHandshakeAcceptedListener =
        registerServerHandshakeAcceptedListener(::handleServerHandshakeAccepted)

    val desiredEnabled: StateFlow<Boolean> = mutableDesiredEnabled.asStateFlow()

    fun setDesiredEnabled(enabled: Boolean) {
        synchronized(stateLock) {
            store.write(enabled)
            mutableDesiredEnabled.value = enabled
        }
    }

    private fun handleServerHandshakeAccepted(origin: PrivilegeRuntimeConnectionOrigin) {
        if (origin != PrivilegeRuntimeConnectionOrigin.INITIAL_LAUNCH) return
        setDesiredEnabled(true)
    }

    override fun close() {
        serverHandshakeAcceptedListener.close()
    }
}

internal object PrivilegeUiDesiredEnabledManagers {
    private val lock = Any()
    private var manager: PrivilegeUiDesiredEnabledManager? = null

    fun get(context: Context): PrivilegeUiDesiredEnabledManager =
        synchronized(lock) {
            manager ?: PrivilegeUiDesiredEnabledManager(context.applicationContext).also {
                manager = it
            }
        }
}
