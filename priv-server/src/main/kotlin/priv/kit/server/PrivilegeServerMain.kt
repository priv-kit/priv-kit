package priv.kit.server

import android.os.Looper
import kotlin.system.exitProcess

object PrivilegeServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            prepareMainLooper()
            val config = PrivilegeServerArguments.parse(args)
            val binder = PrivilegeServerBinder(config)
            val accepted = PrivilegeServerHandshakeSender.send(config, binder)
            if (!accepted) {
                System.err.println("Privileged Server handshake was rejected")
                exitProcess(2)
            }
            keepAlive()
        } catch (throwable: Throwable) {
            throwable.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    @Suppress("DEPRECATION")
    private fun prepareMainLooper() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper()
        }
    }

    private fun keepAlive() {
        Looper.loop()
    }
}
