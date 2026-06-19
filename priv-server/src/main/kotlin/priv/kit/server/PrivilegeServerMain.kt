package priv.kit.server

import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import kotlin.system.exitProcess

object PrivilegeServerMain {
    private var ownerBinder: IBinder? = null
    private val ownerDeathRecipient = IBinder.DeathRecipient {
        Log.i(TAG, "Owner process died; exiting Privileged Server")
        exitProcess(0)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Log.i(TAG, "Privileged Server main entered args=${args.toDiagnosticString()}")
            prepareMainLooper()
            val config = PrivilegeServerArguments.parse(args)
            Log.i(
                TAG,
                "Config parsed package=${config.packageName}, provider=${config.providerAuthority}, " +
                    "mode=${config.mode}, protocol=${config.protocolVersion}, version=${config.serverVersion}",
            )
            val binder = PrivilegeServerBinder(config)
            Log.i(TAG, "Sending handshake uid=${android.os.Process.myUid()}, pid=${android.os.Process.myPid()}")
            val handshakeResult = PrivilegeServerHandshakeSender.send(config, binder)
            Log.i(TAG, "Handshake result accepted=${handshakeResult.accepted}")
            if (!handshakeResult.accepted) {
                System.err.println("Privileged Server handshake was rejected")
                exitProcess(2)
            }
            watchOwner(handshakeResult.ownerBinder)
            keepAlive()
        } catch (throwable: Throwable) {
            Log.e(TAG, "Privileged Server failed before keepAlive", throwable)
            throwable.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private fun watchOwner(binder: IBinder?) {
        if (binder == null) {
            Log.w(TAG, "Handshake did not return an owner Binder; server will not follow app process death")
            return
        }
        try {
            ownerBinder = binder
            binder.linkToDeath(ownerDeathRecipient, 0)
            Log.i(TAG, "Linked Privileged Server lifetime to owner process")
        } catch (e: RemoteException) {
            Log.i(TAG, "Owner process died before death recipient was linked; exiting Privileged Server")
            exitProcess(0)
        }
    }

    @Suppress("DEPRECATION")
    private fun prepareMainLooper() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper()
        }
    }

    private fun keepAlive() {
        Log.i(TAG, "Privileged Server entering Looper")
        Looper.loop()
    }

    private fun Array<String>.toDiagnosticString(): String =
        mapIndexed { index, arg ->
            if (index > 0 && this[index - 1] == "--token") {
                "<redacted>"
            } else {
                arg
            }
        }.joinToString(prefix = "[", postfix = "]") { arg ->
            if (arg.length > 16 && arg.any { it.isLetterOrDigit() }) {
                when {
                    arg == "--token" -> arg
                    else -> arg.take(16) + "..."
                }
            } else {
                arg
            }
        }

    private const val TAG = "PrivKitServer"
}
