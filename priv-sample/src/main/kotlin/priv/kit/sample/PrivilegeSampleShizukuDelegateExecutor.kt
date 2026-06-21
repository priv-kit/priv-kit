package priv.kit.sample

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import priv.kit.core.PrivilegeStartupException
import priv.kit.delegate.PrivilegeDelegateCommand
import priv.kit.delegate.PrivilegeDelegateExecutor
import priv.kit.delegate.PrivilegeDelegateProcess
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class PrivilegeSampleShizukuDelegateExecutor(
    context: Context,
) : PrivilegeDelegateExecutor,
    Closeable {
    private val applicationContext = context.applicationContext

    @Volatile
    private var service: IPrivilegeSampleShizukuDelegateService? = null

    @Volatile
    private var serviceConnection: ServiceConnection? = null

    override val name: String = "Shizuku UserService"

    override fun isAvailable(): Boolean =
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.getVersion() >= SHIZUKU_USER_SERVICE_MIN_VERSION &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    override fun start(command: PrivilegeDelegateCommand): PrivilegeDelegateProcess {
        val activeService = bindOrGetService()
        val output = try {
            activeService.start(command.detachedCommandLine)
        } catch (exception: RemoteException) {
            throw PrivilegeStartupException("Shizuku delegate UserService failed to start command", exception)
        }
        return ShizukuDelegateProcess(activeService, output, ::close)
    }

    override fun close() {
        val connection = serviceConnection ?: return
        serviceConnection = null
        service = null
        runCatching {
            Shizuku.unbindUserService(userServiceArgs(), connection, true)
        }
    }

    private fun bindOrGetService(): IPrivilegeSampleShizukuDelegateService {
        service?.takeIf { service ->
            runCatching { service.asBinder().pingBinder() }.getOrDefault(false)
        }?.let { return it }

        val serviceRef = AtomicReference<IPrivilegeSampleShizukuDelegateService?>()
        val failureRef = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                val delegateService = IPrivilegeSampleShizukuDelegateService.Stub.asInterface(binder)
                if (delegateService == null) {
                    failureRef.set(PrivilegeStartupException("Shizuku delegate UserService returned an invalid Binder"))
                } else {
                    serviceRef.set(delegateService)
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                if (serviceRef.get() == null) {
                    failureRef.set(PrivilegeStartupException("Shizuku delegate UserService disconnected while binding"))
                    latch.countDown()
                }
            }
        }

        try {
            serviceConnection = connection
            Shizuku.bindUserService(userServiceArgs(), connection)
        } catch (throwable: Throwable) {
            serviceConnection = null
            throw PrivilegeStartupException("Failed to bind Shizuku delegate UserService", throwable)
        }

        if (!latch.await(SHIZUKU_BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs(), connection, true)
            }
            serviceConnection = null
            throw PrivilegeStartupException("Timed out binding Shizuku delegate UserService")
        }

        failureRef.get()?.let { throwable ->
            runCatching {
                Shizuku.unbindUserService(userServiceArgs(), connection, true)
            }
            serviceConnection = null
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to bind Shizuku delegate UserService", throwable)
        }

        val boundService = serviceRef.get()
            ?: throw PrivilegeStartupException("Shizuku delegate UserService returned no Binder")
        service = boundService
        return boundService
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(
                applicationContext.packageName,
                PrivilegeSampleShizukuDelegateService::class.java.name,
            ),
        )
            .daemon(false)
            .tag(SHIZUKU_DELEGATE_TAG)
            .processNameSuffix(SHIZUKU_DELEGATE_PROCESS_SUFFIX)
            .debuggable(applicationContext.isDebuggable())
            .version(SHIZUKU_DELEGATE_SERVICE_VERSION)

    private fun Context.isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private class ShizukuDelegateProcess(
        private val service: IPrivilegeSampleShizukuDelegateService,
        private val initialOutput: String,
        private val closeService: () -> Unit,
    ) : PrivilegeDelegateProcess {
        override val isAlive: Boolean
            get() = runCatching { service.isLaunchProcessAlive }.getOrDefault(false)

        override fun destroy() {
            runCatching {
                service.stopLaunchProcess()
            }
            closeService()
        }

        override fun outputText(): String =
            runCatching { service.launchOutput }
                .getOrElse { throwable ->
                    initialOutput.ifBlank {
                        "<failed to read Shizuku delegate output: ${throwable.javaClass.simpleName}: ${throwable.message}>"
                    }
                }
    }

    private companion object {
        const val SHIZUKU_USER_SERVICE_MIN_VERSION = 10
        const val SHIZUKU_BIND_TIMEOUT_MILLIS = 10_000L
        const val SHIZUKU_DELEGATE_TAG = "priv-kit-delegate"
        const val SHIZUKU_DELEGATE_PROCESS_SUFFIX = "priv-kit-shizuku-delegate"
        const val SHIZUKU_DELEGATE_SERVICE_VERSION = 1
    }
}
