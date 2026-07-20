package priv.kit.ui.runtime

import android.content.Context
import priv.kit.shared.PrivilegeBinaryFileStore
import priv.kit.shared.PrivilegeStoragePaths
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import java.nio.charset.StandardCharsets

internal sealed interface PrivilegeUiStartMethod {
    val methodId: String

    data object Root : PrivilegeUiStartMethod {
        override val methodId: String = "root"
    }

    data object AdbWireless : PrivilegeUiStartMethod {
        override val methodId: String = "adb-wireless"
    }

    data object AdbTcpip : PrivilegeUiStartMethod {
        override val methodId: String = "adb-tcpip"
    }

    data class External(val providerId: String) : PrivilegeUiStartMethod {
        override val methodId: String = METHOD_ID_PREFIX + providerId

        companion object {
            private const val METHOD_ID_PREFIX = "external:"

            fun parse(methodId: String): External? {
                if (!methodId.startsWith(METHOD_ID_PREFIX)) return null
                return External(providerId = methodId.removePrefix(METHOD_ID_PREFIX))
            }
        }
    }

    companion object {
        fun parse(methodId: String): PrivilegeUiStartMethod? =
            when (methodId) {
                Root.methodId -> Root
                AdbWireless.methodId -> AdbWireless
                AdbTcpip.methodId -> AdbTcpip
                else -> External.parse(methodId)
            }
    }
}

internal class PrivilegeUiStartMethodStore(context: Context) {
    private val file = PrivilegeStoragePaths.file(
        context = context,
        fileName = START_METHOD_FILE_NAME,
    )

    fun read(): PrivilegeUiStartMethod? =
        synchronized(lock) {
            PrivilegeBinaryFileStore.readIfExists(file)
                ?.let { bytes ->
                    PrivilegeUiStartMethod.parse(
                        String(bytes, StandardCharsets.UTF_8),
                    )
                }
        }

    fun write(method: PrivilegeUiStartMethod) {
        synchronized(lock) {
            PrivilegeBinaryFileStore.writeAtomically(
                file = file,
                bytes = method.methodId.toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    private companion object {
        const val START_METHOD_FILE_NAME = "ui-start-method"
        val lock = Any()
    }
}

internal fun privilegeUiStartMethod(
    source: PrivilegeUiRuntimeStartSource?,
    providerId: String?,
): PrivilegeUiStartMethod? =
    when (source) {
        PrivilegeUiRuntimeStartSource.ROOT -> PrivilegeUiStartMethod.Root
        PrivilegeUiRuntimeStartSource.ADB_WIRELESS -> PrivilegeUiStartMethod.AdbWireless
        PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP -> PrivilegeUiStartMethod.AdbTcpip
        PrivilegeUiRuntimeStartSource.EXTERNAL -> providerId
            ?.let(PrivilegeUiStartMethod::External)
        null -> null
    }
