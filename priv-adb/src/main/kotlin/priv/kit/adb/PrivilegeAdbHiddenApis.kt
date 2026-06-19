package priv.kit.adb

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import javax.net.ssl.SSLSocket

internal object PrivilegeAdbHiddenApis {
    fun exportKeyingMaterial(
        socket: SSLSocket,
        label: String,
        context: ByteArray?,
        length: Int,
    ): ByteArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/Conscrypt;")
            }
        }
        val clazz = Class.forName("com.android.org.conscrypt.Conscrypt")
        val method = clazz.getMethod(
            "exportKeyingMaterial",
            SSLSocket::class.java,
            String::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
        )
        return method.invoke(null, socket, label, context, length) as ByteArray
    }
}
