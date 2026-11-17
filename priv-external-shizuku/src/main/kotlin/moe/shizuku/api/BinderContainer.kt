package moe.shizuku.api

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/** Parcelable encoded by the Shizuku server. */
public open class BinderContainer(@JvmField public val binder: IBinder?) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeStrongBinder(binder)
    }

    private object BinderCreator : Parcelable.Creator<BinderContainer> {
        override fun createFromParcel(source: Parcel): BinderContainer =
            BinderContainer(source.readStrongBinder())

        override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<BinderContainer> = BinderCreator
    }
}
