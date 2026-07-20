package priv.kit.sample

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import priv.kit.core.PrivilegeExternalStartup
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogListener
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class PrivilegeSampleShizukuExternalStarter(
    context: Context,
) : Closeable {
    private val applicationContext = context.applicationContext

    @Volatile
    private var service: IPrivilegeSampleShizukuStartService? = null

    @Volatile
    private var serviceConnection: ServiceConnection? = null

    fun isAvailable(): Boolean =
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.getVersion() >= SHIZUKU_USER_SERVICE_MIN_VERSION &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun start(commandLine: String): String =
        startAndWait(commandLine, startupLogListener = null)

    fun start(
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener,
    ) {
        startAndWait(commandLine, startupLogListener)
    }

    private fun startAndWait(
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String {
        if (!isAvailable()) {
            throw PrivilegeStartupException("Shizuku external starter is not available")
        }
        val activeService = bindOrGetService()
        return PrivilegeExternalStartup.runThroughBridge(
            commandLine = commandLine,
            bridge = { bridgeCommand, stdout, stderr, resultReceiver ->
                activeService.start(bridgeCommand, stdout, stderr, resultReceiver)
            },
            startupLogListener = startupLogListener,
        )
    }

    override fun close() {
        val connection = serviceConnection ?: return
        serviceConnection = null
        service = null
        removeUserService(connection)
    }

    private fun bindOrGetService(): IPrivilegeSampleShizukuStartService {
        service?.takeIf { service ->
            runCatching { service.asBinder().pingBinder() }.getOrDefault(false)
        }?.let { return it }

        val serviceRef = AtomicReference<IPrivilegeSampleShizukuStartService?>()
        val failureRef = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                val startService = IPrivilegeSampleShizukuStartService.Stub.asInterface(binder)
                if (startService == null) {
                    failureRef.set(PrivilegeStartupException("Shizuku UserService returned an invalid Binder"))
                } else {
                    serviceRef.set(startService)
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                if (serviceRef.get() == null) {
                    failureRef.set(PrivilegeStartupException("Shizuku UserService disconnected while binding"))
                    latch.countDown()
                }
            }
        }

        try {
            serviceConnection = connection
            Shizuku.bindUserService(userServiceArgs(), connection)
        } catch (throwable: Throwable) {
            serviceConnection = null
            throw PrivilegeStartupException("Failed to bind Shizuku UserService", throwable)
        }

        if (!latch.await(SHIZUKU_BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            removeUserService(connection)
            serviceConnection = null
            throw PrivilegeStartupException("Timed out binding Shizuku UserService")
        }

        failureRef.get()?.let { throwable ->
            removeUserService(connection)
            serviceConnection = null
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to bind Shizuku UserService", throwable)
        }

        val boundService = serviceRef.get()
            ?: throw PrivilegeStartupException("Shizuku UserService returned no Binder")
        service = boundService
        return boundService
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(
                applicationContext.packageName,
                PrivilegeSampleShizukuStartService::class.java.name,
            ),
        )
            .daemon(false)
            .tag(SHIZUKU_START_TAG)
            .processNameSuffix(SHIZUKU_START_PROCESS_SUFFIX)
            .debuggable(applicationContext.isDebuggable())
            .version(SHIZUKU_START_SERVICE_VERSION)

    private fun removeUserService(connection: ServiceConnection) {
        val args = userServiceArgs()
        // remove=false clears Shizuku's client-side connection cache; remove=true destroys the remote record.
        runCatching {
            Shizuku.unbindUserService(args, connection, false)
        }
        runCatching {
            Shizuku.unbindUserService(args, connection, true)
        }
    }

    private fun Context.isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        internal const val SHIZUKU_USER_SERVICE_MIN_VERSION = 10
        private const val SHIZUKU_BIND_TIMEOUT_MILLIS = 10_000L
        private const val SHIZUKU_START_TAG = "priv-kit-external-start"
        private const val SHIZUKU_START_PROCESS_SUFFIX = "priv-kit-shizuku-start"
        // Bump whenever the UserService implementation or its AIDL contract changes.
        private const val SHIZUKU_START_SERVICE_VERSION = 4
    }
}
