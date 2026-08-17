package priv.kit.core.internal.file

import android.system.StructStat
import priv.kit.core.file.PrivilegeFileDirectoryEntry
import priv.kit.core.file.PrivilegeFileMetadata
import java.io.DataInputStream
import java.io.DataOutputStream

internal object PrivilegeFileWire {
    fun statToArray(stat: StructStat): LongArray = longArrayOf(
        stat.st_mode.toLong(),
        stat.st_size,
        stat.st_mtime,
        stat.st_uid.toLong(),
        stat.st_gid.toLong(),
    )

    fun metadataFromArray(
        path: String,
        values: LongArray,
    ): PrivilegeFileMetadata {
        check(values.size == PrivilegeFileSystemContract.STAT_FIELD_COUNT) {
            "Invalid file metadata field count: ${values.size}"
        }
        val mode = values[PrivilegeFileSystemContract.STAT_MODE].toInt()
        return PrivilegeFileMetadata(
            absolutePath = path,
            sizeBytes = values[PrivilegeFileSystemContract.STAT_SIZE],
            lastModifiedMillis =
                values[PrivilegeFileSystemContract.STAT_LAST_MODIFIED_SECONDS] * 1_000L,
            unixMode = mode,
            uid = values[PrivilegeFileSystemContract.STAT_UID].toInt(),
            gid = values[PrivilegeFileSystemContract.STAT_GID].toInt(),
        )
    }

    fun writeEntry(
        output: DataOutputStream,
        path: String,
        stat: StructStat?,
    ) {
        output.writeByte(PrivilegeFileSystemContract.SCAN_ENTRY)
        output.writeUTF(path)
        output.writeBoolean(stat != null)
        if (stat != null) {
            statToArray(stat).forEach(output::writeLong)
        }
    }

    fun readEntry(input: DataInputStream): PrivilegeFileDirectoryEntry {
        val path = input.readUTF()
        val metadata = if (input.readBoolean()) {
            val values = LongArray(PrivilegeFileSystemContract.STAT_FIELD_COUNT) {
                input.readLong()
            }
            metadataFromArray(path, values)
        } else {
            null
        }
        return PrivilegeFileDirectoryEntry(path, metadata)
    }
}
