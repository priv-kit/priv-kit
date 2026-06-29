package priv.kit.internal.server

import android.os.Process
import priv.kit.internal.core.PrivilegeProtocol
import java.io.File

internal object PrivilegeServerArguments {
    fun parse(
        args: Array<String>,
        classpath: String = System.getenv("CLASSPATH").orEmpty(),
        uid: Int = Process.myUid(),
    ): PrivilegeServerConfig {
        require(args.isEmpty()) { "Privileged Server no longer accepts launch arguments" }
        val normalizedClasspath = classpath.trim()
        require(normalizedClasspath.isNotBlank()) { "Server classpath is unavailable" }
        val packageName = inferPackageName(normalizedClasspath)
        return PrivilegeServerConfig(
            packageName = packageName,
            userId = userIdFromUid(uid),
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

    private fun userIdFromUid(uid: Int): Int =
        if (uid >= PER_USER_RANGE) uid / PER_USER_RANGE else 0

    private const val PER_USER_RANGE = 100_000
}
