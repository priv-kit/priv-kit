package priv.kit.core.internal.binder

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import priv.kit.core.binder.PrivilegeBinderWrapper

/** Shared raw transaction endpoint, hosted by the server or a UserService process. */
internal class PrivilegeBinderForwarder(
    private val serviceResolver: (String) -> IBinder? = { ServiceManager.getService(it) },
) : Binder() {
    private val systemServices = HashMap<String, IBinder>()

    @Synchronized
    fun getSystemService(name: String): IBinder? {
        require(name.isNotBlank()) { "System service name must not be blank" }
        return systemServices[name] ?: serviceResolver(name)?.also { systemServices[name] = it }
    }

    fun handles(code: Int): Boolean =
        code == PrivilegeBinderWrapper.TRANSACTION_TRANSACT_BINDER || code == TRANSACTION_HAS_SERVICE

    fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        data.enforceInterface(PrivilegeBinderWrapper.DESCRIPTOR)
        val identity = clearCallingIdentity()
        try {
            if (code == TRANSACTION_HAS_SERVICE) {
                val exists = getSystemService(requireNotNull(data.readString())) != null
                requireNotNull(reply).writeNoException()
                reply.writeInt(if (exists) 1 else 0)
                return true
            }
            val target = when (val kind = data.readInt()) {
                PrivilegeBinderWrapper.TARGET_BINDER -> requireNotNull(data.readStrongBinder())
                PrivilegeBinderWrapper.TARGET_SYSTEM_SERVICE -> {
                    val name = requireNotNull(data.readString())
                    checkNotNull(getSystemService(name)) { "System service not found: $name" }
                }
                else -> error("Unknown remote Binder target kind: $kind")
            }
            val targetCode = data.readInt()
            val targetFlags = data.readInt()
            val targetData = Parcel.obtain()
            return try {
                targetData.appendFrom(data, data.dataPosition(), data.dataAvail())
                targetData.setDataPosition(0)
                target.transact(targetCode, targetData, reply, targetFlags)
            } finally {
                targetData.recycle()
            }
        } finally {
            restoreCallingIdentity(identity)
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        if (handles(code)) dispatch(code, data, reply) else super.onTransact(code, data, reply, flags)

    companion object {
        const val TRANSACTION_HAS_SERVICE: Int = 0x00FF0002

        fun hasSystemService(endpoint: IBinder, name: String): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(PrivilegeBinderWrapper.DESCRIPTOR)
                data.writeString(name)
                check(endpoint.transact(TRANSACTION_HAS_SERVICE, data, reply, 0)) {
                    "Binder endpoint does not support system service lookup"
                }
                reply.readException()
                reply.readInt() != 0
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}
