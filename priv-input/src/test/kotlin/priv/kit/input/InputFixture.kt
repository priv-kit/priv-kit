package priv.kit.input

import android.os.Binder
import android.os.IInterface
import android.os.Parcel
import android.view.InputEvent
import java.lang.reflect.Proxy

internal fun inputFixture(
    inject: (InputEvent, Int) -> Boolean = { _, _ -> error("Unexpected event injection") },
): PrivilegeInput {
    val type = Class.forName("android.hardware.input.IInputManager")
    val manager = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        check(method.name == "injectInputEvent") { "Unexpected input service call: ${method.name}" }
        inject(args[0] as InputEvent, args[1] as Int)
    } as IInterface
    val binder = object : Binder() {
        override fun queryLocalInterface(descriptor: String): IInterface = manager
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            error("Unexpected Binder transaction: $code")

    }
    return PrivilegeInput(binder)
}

