package priv.kit.external.shizuku.internal

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuServiceConnection
import priv.kit.core.PrivilegeExternalStartup
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogListener

internal object ShizukuStarter {
    private const val TAG = "PrivilegeExternalShizuku"
    private const val BIND_TIMEOUT_MILLIS = 10_000L
    private const val SERVICE_TAG = "priv-external-shizuku"
    private const val REMOVE_ARG = "shizuku:user-service-remove"

    private val startup = Mutex()

    suspend fun run(
        context: Context,
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String = withContext(Dispatchers.IO) {
        // The service tag identifies one remote record shared by all calls in this process.
        startup.withLock {
            val session = ShizukuClient.requireAuthorizedSession()
            val component = ComponentName(context.packageName, ShizukuStartService::class.java.name)
            val args = Bundle().apply {
                putParcelable("shizuku:user-service-arg-component", component)
                putString("shizuku:user-service-arg-tag", SERVICE_TAG)
                putString("shizuku:user-service-arg-process-name", "priv-shizuku-start")
                putBoolean("shizuku:user-service-arg-daemon", false)
            }
            val connection = ServiceConnection()
            val result = session.service.addUserService(connection, args)
            if (result != 0) throw PrivilegeStartupException("Shizuku rejected the startup UserService ($result)")

            try {
                coroutineScope {
                    val start = async {
                        val binder = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) {
                            connection.connected.await()
                        } ?: throw PrivilegeStartupException("Timed out binding the Shizuku startup UserService")
                        try {
                            binder.linkToDeath(connection, 0)
                        } catch (_: DeadObjectException) {
                            disconnected()
                        }
                        try {
                            if (session.closed.isCompleted || connection.disconnected.isCompleted) disconnected()
                            val endpoint = IShizukuStartService.Stub.asInterface(binder)
                            PrivilegeExternalStartup.runThroughBridge(
                                commandLine = commandLine,
                                bridge = endpoint::start,
                                startupLogListener = startupLogListener,
                            )
                        } finally {
                            binder.unlinkToDeath(connection, 0)
                        }
                    }
                    select {
                        start.onAwait { it }
                        session.closed.onAwait { disconnected() }
                        connection.disconnected.onAwait { disconnected() }
                    }
                }
            } finally {
                try {
                    args.putBoolean(REMOVE_ARG, true)
                    session.service.removeUserService(null, args)
                } catch (exception: Exception) {
                    Log.w(TAG, "Failed to remove the Shizuku startup UserService", exception)
                }
            }
        }
    }

    private class ServiceConnection : IShizukuServiceConnection.Stub(), IBinder.DeathRecipient {
        val connected = CompletableDeferred<IBinder>()
        val disconnected = CompletableDeferred<Unit>()

        override fun connected(service: IBinder) {
            connected.complete(service)
        }

        override fun died() {
            disconnected.complete(Unit)
        }

        override fun binderDied() = died()
    }

    private fun disconnected(): Nothing =
        throw PrivilegeStartupException("Shizuku disconnected during startup")
}
