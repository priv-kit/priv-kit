package priv.kit.core.internal.file

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

internal class PrivilegeFileResult private constructor(
    val errno: Int,
    val values: LongArray?,
    val fileDescriptor: ParcelFileDescriptor?,
    val completionDescriptor: ParcelFileDescriptor?,
) : Parcelable {
    private constructor(source: Parcel) : this(
        errno = source.readInt(),
        values = source.createLongArray(),
        fileDescriptor = @Suppress("DEPRECATION") source.readParcelable(ParcelFileDescriptor::class.java.classLoader),
        completionDescriptor = @Suppress("DEPRECATION") source.readParcelable(ParcelFileDescriptor::class.java.classLoader),
    )

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeInt(errno)
        destination.writeLongArray(values)
        destination.writeParcelable(fileDescriptor, flags)
        destination.writeParcelable(completionDescriptor, flags)
    }

    override fun describeContents(): Int =
        if (fileDescriptor == null && completionDescriptor == null) {
            0
        } else {
            Parcelable.CONTENTS_FILE_DESCRIPTOR
        }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<PrivilegeFileResult> =
            object : Parcelable.Creator<PrivilegeFileResult> {
                override fun createFromParcel(source: Parcel): PrivilegeFileResult =
                    PrivilegeFileResult(source)

                override fun newArray(size: Int): Array<PrivilegeFileResult?> = arrayOfNulls(size)
            }

        fun metadata(values: LongArray): PrivilegeFileResult =
            PrivilegeFileResult(
                errno = 0,
                values = values,
                fileDescriptor = null,
                completionDescriptor = null,
            )

        fun input(value: ParcelFileDescriptor): PrivilegeFileResult =
            PrivilegeFileResult(
                errno = 0,
                values = null,
                fileDescriptor = value,
                completionDescriptor = null,
            )

        fun output(
            value: ParcelFileDescriptor,
            completion: ParcelFileDescriptor,
        ): PrivilegeFileResult =
            PrivilegeFileResult(
                errno = 0,
                values = null,
                fileDescriptor = value,
                completionDescriptor = completion,
            )

        fun error(errno: Int): PrivilegeFileResult =
            PrivilegeFileResult(
                errno = errno,
                values = null,
                fileDescriptor = null,
                completionDescriptor = null,
            )
    }
}
