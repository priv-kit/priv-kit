package priv.kit.server

import priv.kit.core.PrivilegeMode
import priv.kit.core.PrivilegeProtocol

internal object PrivilegeServerArguments {
    fun parse(args: Array<String>): PrivilegeServerConfig {
        val values = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index]
            require(key.startsWith("--")) { "Unexpected argument: $key" }
            val valueIndex = index + 1
            require(valueIndex < args.size) { "Missing value for $key" }
            values[key.removePrefix("--")] = args[valueIndex]
            index += 2
        }

        return PrivilegeServerConfig(
            token = values.required("token"),
            providerAuthority = values.required("provider-authority"),
            packageName = values.required("package-name"),
            mode = values["mode"]?.toIntOrNull() ?: PrivilegeMode.ROOT.value,
            protocolVersion = values["protocol-version"]?.toIntOrNull() ?: PrivilegeProtocol.VERSION,
            serverVersion = values["server-version"] ?: PrivilegeProtocol.SERVER_VERSION,
        )
    }

    private fun Map<String, String>.required(key: String): String =
        requireNotNull(this[key]?.takeIf { it.isNotBlank() }) { "Missing required argument --$key" }
}
