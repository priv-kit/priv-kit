package priv.kit.sample.userservice

import android.content.Context
import android.os.IBinder
import androidx.annotation.Keep
import priv.kit.evdev.PrivilegeEvdevController

/** The application owns service startup and hands off the library's controller Binder. */
internal class PrivilegeSampleInputService @Keep constructor(
    context: Context,
) : IPrivilegeSampleInputService.Stub() {
    private val input = PrivilegeEvdevController.createLocal(context)

    override fun getDeviceController(): IBinder = input.asBinder()

    override fun destroy() {
        input.close()
    }
}
