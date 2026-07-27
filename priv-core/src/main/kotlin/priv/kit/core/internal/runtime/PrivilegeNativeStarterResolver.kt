package priv.kit.core.internal.runtime

import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import priv.kit.core.PrivilegeStartupException
import priv.kit.shared.toPrivilegeShellArgument

internal sealed interface PrivilegeNativeStarterLocation {
    val path: String

    class InstalledFile(
        override val path: String,
    ) : PrivilegeNativeStarterLocation

    class ApkEntry(
        apkPath: String,
        entryName: String,
        val is64Bit: Boolean,
    ) : PrivilegeNativeStarterLocation {
        override val path: String = "$apkPath!/$entryName"
    }
}

internal object PrivilegeNativeStarterResolver {
    fun resolve(): PrivilegeNativeStarterLocation {
        val applicationInfo = PrivilegeContext.require().applicationInfo
        val apkPaths = buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs?.sorted()?.forEach(::add)
        }
        return resolve(
            sdkInt = Build.VERSION.SDK_INT,
            apkPaths = apkPaths,
            nativeLibraryDir = applicationInfo.nativeLibraryDir,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toSet(),
            extractNativeLibs =
                applicationInfo.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0,
        )
    }

    internal fun resolve(
        sdkInt: Int,
        apkPaths: List<String>,
        nativeLibraryDir: String,
        supportedAbis: List<String>,
        supported64BitAbis: Set<String>,
        extractNativeLibs: Boolean,
    ): PrivilegeNativeStarterLocation {
        val installedPath =
            "${nativeLibraryDir.trimEnd('/')}/$NATIVE_STARTER_LIBRARY_NAME"

        // With extractNativeLibs=false, Android's installer rejects native entries that are
        // compressed or not page-aligned. That platform check makes a STORED entry safe to
        // hand to the linker without parsing private ZIP offsets here.
        if (sdkInt >= Build.VERSION_CODES.Q && !extractNativeLibs) {
            findApkEntry(
                apkPaths = apkPaths,
                supportedAbis = supportedAbis,
                supported64BitAbis = supported64BitAbis,
            )?.let { return it }
        }
        if (File(installedPath).isFile) {
            return PrivilegeNativeStarterLocation.InstalledFile(installedPath)
        }

        if (sdkInt < Build.VERSION_CODES.Q) {
            throw PrivilegeStartupException(
                "minSdk < 29 requires packaging.jniLibs." +
                    "useLegacyPackaging = true so $NATIVE_STARTER_LIBRARY_NAME is extracted; " +
                    "no installed starter was found at $installedPath",
            )
        }
        throw PrivilegeStartupException(
            "No usable $NATIVE_STARTER_LIBRARY_NAME was found in the app APKs or at " +
                "$installedPath; the APK entry must be uncompressed for direct linker startup",
        )
    }

    internal fun commandLine(location: PrivilegeNativeStarterLocation): String =
        when (location) {
            is PrivilegeNativeStarterLocation.InstalledFile ->
                location.path.toPrivilegeShellArgument()
            is PrivilegeNativeStarterLocation.ApkEntry -> {
                val linkerPath = if (location.is64Bit) {
                    LINKER_64_PATH
                } else {
                    LINKER_32_PATH
                }
                "$linkerPath ${location.path.toPrivilegeShellArgument()}"
            }
        }

    private fun findApkEntry(
        apkPaths: List<String>,
        supportedAbis: List<String>,
        supported64BitAbis: Set<String>,
    ): PrivilegeNativeStarterLocation.ApkEntry? {
        apkPaths.forEach { apkPath ->
            val entryName = findStoredEntry(apkPath, supportedAbis) ?: return@forEach
            val abi = entryName.substringAfter("lib/").substringBefore('/')
            return PrivilegeNativeStarterLocation.ApkEntry(
                apkPath = apkPath,
                entryName = entryName,
                is64Bit = abi in supported64BitAbis,
            )
        }
        return null
    }

    private fun findStoredEntry(
        apkPath: String,
        supportedAbis: List<String>,
    ): String? =
        try {
            ZipFile(apkPath).use { zipFile ->
                supportedAbis.firstNotNullOfOrNull { abi ->
                    val entryName = "lib/$abi/$NATIVE_STARTER_LIBRARY_NAME"
                    zipFile.getEntry(entryName)
                        ?.takeIf { it.method == ZipEntry.STORED }
                        ?.let { entryName }
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private const val LINKER_32_PATH = "/system/bin/linker"
    private const val LINKER_64_PATH = "/system/bin/linker64"
    private const val NATIVE_STARTER_LIBRARY_NAME = "libprivkitstarter.so"
}
