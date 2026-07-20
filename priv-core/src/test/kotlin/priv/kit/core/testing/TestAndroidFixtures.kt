package priv.kit.core.testing

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import priv.kit.core.internal.userservice.IPrivilegeUserServiceProcess
import priv.kit.core.internal.userservice.PrivilegeUserServiceHost
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

open class TestBinder(
    private val localInterface: IInterface? = null,
    @Volatile
    private var alive: Boolean = true,
) : IBinder {
    private val deathRecipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
    val deathRecipientCount: Int
        get() = deathRecipients.size

    override fun getInterfaceDescriptor(): String = "fake"

    override fun pingBinder(): Boolean = alive

    override fun isBinderAlive(): Boolean = alive

    override fun queryLocalInterface(descriptor: String): IInterface? = localInterface

    override fun dump(
        fd: FileDescriptor,
        args: Array<out String>?,
    ) = Unit

    override fun dumpAsync(
        fd: FileDescriptor,
        args: Array<out String>?,
    ) = Unit

    open override fun transact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean = alive

    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        if (!alive) {
            throw RemoteException("Binder is dead")
        }
        deathRecipients += recipient
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean =
        deathRecipients.remove(recipient)

    fun killBinder(notifyDeathRecipients: Boolean = true) {
        if (!alive) return
        alive = false
        if (notifyDeathRecipients) {
            deathRecipients.forEach { it.binderDied() }
            deathRecipients.clear()
        }
    }
}

open class TestProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() = Unit
}

open class TestUserServiceProcess : IPrivilegeUserServiceProcess {
    private val binder = TestBinder(localInterface = this)

    override fun asBinder(): IBinder = binder

    override fun start() = Unit

    override fun bind(): IBinder = binder

    override fun unbind(connectionId: String) = Unit

    override fun destroy() = Unit

    fun killBinder() {
        binder.killBinder()
    }
}

open class TestEmbeddedUserServiceHost : PrivilegeUserServiceHost {
    override val uid: Int = 0
    override val pid: Int = 1234
    override val packageName: String = "priv.kit.test"
    override val userId: Int = 0

    open override fun startDedicatedProcess(
        spec: PrivilegeUserServiceSpec,
        token: String,
    ): Process {
        error("Dedicated process is not used by this test")
    }

    open override fun awaitDedicatedProcess(
        token: String,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess {
        error("Dedicated process is not used by this test")
    }

    open override fun killDedicatedProcess(process: Process) = Unit
}

open class TestDedicatedUserServiceHost(
    var process: IPrivilegeUserServiceProcess = TestUserServiceProcess(),
) : PrivilegeUserServiceHost {
    override val uid: Int = 0
    override val pid: Int = 1234
    override val packageName: String = "priv.kit.test"
    override val userId: Int = 0

    open override fun startDedicatedProcess(
        spec: PrivilegeUserServiceSpec,
        token: String,
    ): Process =
        TestProcess()

    open override fun awaitDedicatedProcess(
        token: String,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess = process

    open override fun killDedicatedProcess(process: Process) = Unit
}
