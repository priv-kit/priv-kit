package android.os;

import java.io.FileDescriptor;

import li.songe.remap.RemapType;

@RemapType(Parcel.class)
public class ParcelHidden {
    public void writeFileDescriptor(FileDescriptor value) {
    }
}
