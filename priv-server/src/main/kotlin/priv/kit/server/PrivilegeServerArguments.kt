package priv.kit.server

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
            launchMode = values.requiredInt("launch-mode"),
            protocolVersion = values.requiredInt("protocol-version"),
            serverVersion = values.required("server-version"),
            followDeathDelayMillis = values.requiredNonNegativeLong("follow-death-delay-millis"),
            activeReconnectOnOwnerDeath = values.requiredBoolean("active-reconnect-on-owner-death"),
        )
    }

    private fun Map<String, String>.required(key: String): String =
        requireNotNull(this[key]?.takeIf { it.isNotBlank() }) { "Missing required argument --$key" }

    private fun Map<String, String>.requiredInt(key: String): Int {
        val rawValue = required(key)
        return rawValue.toIntOrNull()
            ?: throw IllegalArgumentException("--$key must be an integer")
    }

    private fun Map<String, String>.requiredNonNegativeLong(key: String): Long {
        val rawValue = required(key)
        val value = rawValue.toLongOrNull()
        require(value != null && value >= 0L) { "--$key must be a non-negative millisecond value" }
        return value
    }

    private fun Map<String, String>.requiredBoolean(key: String): Boolean {
        val rawValue = required(key)
        return when (rawValue.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("--$key must be true or false")
        }
    }
}
