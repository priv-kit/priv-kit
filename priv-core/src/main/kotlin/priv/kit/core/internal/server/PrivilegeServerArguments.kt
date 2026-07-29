package priv.kit.core.internal.server

import priv.kit.core.internal.core.PrivilegeProtocol
import java.io.File

internal object PrivilegeServerArguments {
    fun parse(
        args: Array<String>,
        classpath: String,
        launchCorrelationId: String?,
        ownerUserId: String?,
    ): PrivilegeServerConfig {
        require(args.isEmpty()) { "Privileged Server no longer accepts launch arguments" }
        val normalizedClasspath = classpath.trim()
        val packageName = inferPackageName(normalizedClasspath)
        val parsedOwnerUserId = if (ownerUserId == null) {
            0
        } else {
            ownerUserId
                .toIntOrNull()
                ?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException(
                    "Privileged Server owner userId is invalid",
                )
        }
        return PrivilegeServerConfig(
            launchCorrelationId = launchCorrelationId,
            packageName = packageName,
            userId = parsedOwnerUserId,
            classpath = normalizedClasspath,
            protocolVersion = PrivilegeProtocol.VERSION,
        )
    }

    private fun inferPackageName(classpath: String): String {
        val firstPath = classpath.split(':').firstOrNull { it.isNotBlank() }
            ?: throw IllegalArgumentException("Server classpath is empty")
        val installDirectory = File(firstPath).parentFile
            ?: throw IllegalArgumentException("Failed to infer install directory from classpath: $firstPath")
        val directoryName = installDirectory.name
        val packageName = directoryName.substringBefore('-').takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Failed to infer package name from classpath: $firstPath")
        return packageName
    }

}
