package android.os;

import java.io.FileDescriptor;

import li.songe.remap.RemapType;

@RemapType(IBinder.class)
public interface IBinderHidden {
    void shellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback shellCallback, ResultReceiver resultReceiver);
}
