package priv.kit.core.internal.runtime

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal object PrivilegeFallbackCrashLogs {
    val root = File("/data/local/tmp")
    const val INDEX_NAME = "index.txt"
    private val reportName = Regex("priv-crash_uid[0-9]+_[0-9]{14}\\.json")

    fun directory(packageName: String, userId: Int): Path =
        segments(packageName, userId).fold(root.toPath()) { path, segment -> path.resolve(segment) }

    fun createDirectory(root: File, packageName: String, userId: Int): File {
        var directory = root.toPath()
        for (segment in segments(packageName, userId)) {
            directory = directory.resolve(segment)
            try {
                Files.createDirectory(directory)
            } catch (exists: FileAlreadyExistsException) {
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw exists
            }
        }
        return directory.toFile()
    }

    fun listFiles(
        directory: Path,
        openDirectory: (Path) -> DirectoryStream<Path> = Files::newDirectoryStream,
    ): List<File> {
        val listingFailure = try {
            val stream = try {
                openDirectory(directory)
            } catch (_: NoSuchFileException) {
                return emptyList()
            }
            return stream.use { entries ->
                buildList {
                    for (path in entries) {
                        if (reportName.matches(path.fileName.toString()) && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                            add(path.toFile())
                        }
                    }
                }
            }
        } catch (failure: DirectoryIteratorException) {
            failure.cause ?: IOException("Unable to list crash logs in $directory", failure)
        } catch (failure: IOException) {
            failure
        }

        val index = directory.resolve(INDEX_NAME)
        try {
            if (!Files.readAttributes(index, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile) {
                throw IOException("Crash log index is not a regular file: $index")
            }
            return Files.newInputStream(index, NOFOLLOW_LINKS).bufferedReader().useLines { lines ->
                lines.filter(reportName::matches)
                    .distinct()
                    .map(directory::resolve)
                    .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .map(Path::toFile)
                    .toList()
            }
        } catch (indexFailure: IOException) {
            listingFailure.addSuppressed(indexFailure)
            throw listingFailure
        }
    }

    private fun segments(packageName: String, userId: Int): List<String> {
        val appId = packageName.map {
            if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_') it else '_'
        }.joinToString("")
        require(appId.isNotEmpty() && appId != "." && appId != "..")
        return listOf("priv-kit", appId, userId.toString(), "crashes")
    }
}
