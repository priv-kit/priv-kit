package priv.kit.core.internal.server

import android.os.Process
import priv.kit.core.internal.core.PrivilegeAndroidUsers
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.core.PrivilegeProtocol
import java.io.File

internal object PrivilegeServerArguments {
    fun parse(
        args: Array<String>,
        classpath: String,
        launchCorrelationId: String?,
        uid: Int,
    ): PrivilegeServerConfig {
        require(args.isEmpty()) { "Privileged Server no longer accepts launch arguments" }
        val normalizedClasspath = classpath.trim()
        require(normalizedClasspath.isNotBlank()) { "Server classpath is unavailable" }
        val packageName = inferPackageName(normalizedClasspath)
        return PrivilegeServerConfig(
            launchCorrelationId = launchCorrelationId,
            packageName = packageName,
            userId = PrivilegeAndroidUsers.userIdFromUid(uid),
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
