package priv.kit.core.internal.runtime

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import priv.kit.core.PrivilegeStartupException

class PrivilegeNativeStarterResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun android10UsesUncompressedStarterFromBaseApk() {
        val baseApk = writeApk(
            name = "base.apk",
            entryName = ARM64_STARTER_ENTRY,
            stored = true,
        )

        val location = PrivilegeNativeStarterResolver.resolve(
            sdkInt = 29,
            apkPaths = listOf(baseApk.path),
            nativeLibraryDir = "/data/app/priv.kit.sample/lib/arm64",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            supported64BitAbis = setOf("arm64-v8a"),
            extractNativeLibs = false,
        )

        assertTrue(location is PrivilegeNativeStarterLocation.ApkEntry)
        assertEquals(
            "${baseApk.path}!/$ARM64_STARTER_ENTRY",
            location.path,
        )
        assertEquals(
            "/system/bin/linker64 '${baseApk.path}!/$ARM64_STARTER_ENTRY'",
            PrivilegeNativeStarterResolver.commandLine(location),
        )
    }

    @Test
    fun android10FindsStarterInAbiSplit() {
        val baseApk = writeApk(
            name = "base.apk",
            entryName = "assets/empty",
            stored = true,
        )
        val splitApk = writeApk(
            name = "split_config.arm64_v8a.apk",
            entryName = ARM64_STARTER_ENTRY,
            stored = true,
        )

        val location = PrivilegeNativeStarterResolver.resolve(
            sdkInt = 29,
            apkPaths = listOf(baseApk.path, splitApk.path),
            nativeLibraryDir = "/data/app/priv.kit.sample/lib/arm64",
            supportedAbis = listOf("arm64-v8a"),
            supported64BitAbis = setOf("arm64-v8a"),
            extractNativeLibs = false,
        )

        assertEquals(
            "${splitApk.path}!/$ARM64_STARTER_ENTRY",
            location.path,
        )
    }

    @Test
    fun android10Uses32BitLinkerFor32BitStarter() {
        val baseApk = writeApk(
            name = "base.apk",
            entryName = ARM32_STARTER_ENTRY,
            stored = true,
        )

        val location = PrivilegeNativeStarterResolver.resolve(
            sdkInt = 29,
            apkPaths = listOf(baseApk.path),
            nativeLibraryDir = "/data/app/priv.kit.sample/lib/arm",
            supportedAbis = listOf("armeabi-v7a"),
            supported64BitAbis = emptySet(),
            extractNativeLibs = false,
        )

        assertEquals(
            "/system/bin/linker '${baseApk.path}!/$ARM32_STARTER_ENTRY'",
            PrivilegeNativeStarterResolver.commandLine(location),
        )
    }

    @Test
    fun android10UsesPlatform64BitAbiSetForNewAbi() {
        val future64Entry = "lib/future64/libprivkitstarter.so"
        val baseApk = writeApk(
            name = "base.apk",
            entryName = future64Entry,
            stored = true,
        )

        val location = PrivilegeNativeStarterResolver.resolve(
            sdkInt = 29,
            apkPaths = listOf(baseApk.path),
            nativeLibraryDir = "/data/app/priv.kit.sample/lib/future64",
            supportedAbis = listOf("future64"),
            supported64BitAbis = setOf("future64"),
            extractNativeLibs = false,
        )

        assertTrue(
            PrivilegeNativeStarterResolver.commandLine(location)
                .startsWith("/system/bin/linker64 "),
        )
    }

    @Test
    fun android10UsesExtractedStarterWhenLegacyPackagingIsEnabled() {
        val baseApk = writeApk(
            name = "base.apk",
            entryName = ARM64_STARTER_ENTRY,
            stored = true,
        )
        val nativeLibraryDir = temporaryFolder.newFolder("native", "arm64")
        val installedStarter = File(nativeLibraryDir, "libprivkitstarter.so")
            .apply { writeBytes(byteArrayOf(1)) }

        val location = PrivilegeNativeStarterResolver.resolve(
            sdkInt = 29,
            apkPaths = listOf(baseApk.path),
            nativeLibraryDir = nativeLibraryDir.path,
            supportedAbis = listOf("arm64-v8a"),
            supported64BitAbis = setOf("arm64-v8a"),
            extractNativeLibs = true,
        )

        assertTrue(location is PrivilegeNativeStarterLocation.InstalledFile)
        assertEquals(
            installedStarter.path.replace('\\', '/'),
            location.path.replace('\\', '/'),
        )
    }

    @Test
    fun android10FallsBackToExtractedStarterWhenApkEntryIsMissing() {
        val baseApk = writeApk(
            name = "base.apk",
            entryName = "assets/empty",
            stored = true,
        )
        val nativeLibraryDir = temporaryFolder.newFolder("fallback", "arm64")
        val installedStarter = File(nativeLibraryDir, "libprivkitstarter.so")
            .apply { writeBytes(byteArrayOf(1)) }

        val location = PrivilegeNativeStarterResolver.resolve(
            sdkInt = 29,
            apkPaths = listOf(baseApk.path),
            nativeLibraryDir = nativeLibraryDir.path,
            supportedAbis = listOf("arm64-v8a"),
            supported64BitAbis = setOf("arm64-v8a"),
            extractNativeLibs = false,
        )

        assertTrue(location is PrivilegeNativeStarterLocation.InstalledFile)
        assertEquals(
            installedStarter.path.replace('\\', '/'),
            location.path.replace('\\', '/'),
        )
    }

    @Test
    fun android10RejectsCompressedStarterWhenExtractionIsDisabled() {
        val baseApk = writeApk(
            name = "base.apk",
            entryName = ARM64_STARTER_ENTRY,
            stored = false,
        )

        val exception = assertThrows(PrivilegeStartupException::class.java) {
            PrivilegeNativeStarterResolver.resolve(
                sdkInt = 29,
                apkPaths = listOf(baseApk.path),
                nativeLibraryDir = "/data/app/priv.kit.sample/lib/arm64",
                supportedAbis = listOf("arm64-v8a"),
                supported64BitAbis = setOf("arm64-v8a"),
                extractNativeLibs = false,
            )
        }

        assertTrue(exception.message!!.contains("must be uncompressed"))
    }

    @Test
    fun android9RequiresLegacyPackagingWhenExtractedStarterIsMissing() {
        val exception = assertThrows(PrivilegeStartupException::class.java) {
            PrivilegeNativeStarterResolver.resolve(
                sdkInt = 28,
                apkPaths = emptyList(),
                nativeLibraryDir = "/data/app/priv.kit.sample/lib/arm64",
                supportedAbis = listOf("arm64-v8a"),
                supported64BitAbis = setOf("arm64-v8a"),
                extractNativeLibs = false,
            )
        }

        assertTrue(exception.message!!.contains("useLegacyPackaging = true"))
        assertTrue(exception.message!!.contains("minSdk < 29"))
    }

    private fun writeApk(
        name: String,
        entryName: String,
        stored: Boolean,
    ): File {
        val apk = temporaryFolder.newFile(name)
        val content = "starter".toByteArray()
        ZipOutputStream(apk.outputStream()).use { output ->
            val entry = ZipEntry(entryName)
            if (stored) {
                val crc = CRC32().apply { update(content) }
                entry.method = ZipEntry.STORED
                entry.size = content.size.toLong()
                entry.compressedSize = content.size.toLong()
                entry.crc = crc.value
            }
            output.putNextEntry(entry)
            output.write(content)
            output.closeEntry()
        }
        return apk
    }

    private companion object {
        const val ARM32_STARTER_ENTRY = "lib/armeabi-v7a/libprivkitstarter.so"
        const val ARM64_STARTER_ENTRY = "lib/arm64-v8a/libprivkitstarter.so"
    }
}
