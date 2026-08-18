package priv.kit.core.internal.file;

import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import priv.kit.core.internal.file.PrivilegeFileResult;

interface IPrivilegeFileSystem {
    boolean query(String path, int kind);
    long queryLong(String path, int kind);
    PrivilegeFileResult stat(String path, boolean followSymbolicLinks);
    PrivilegeFileResult openInput(String path);
    PrivilegeFileResult openOutput(String path, boolean append, boolean syncOnClose);
    int createNewFile(String path);
    boolean mkdir(String path);
    boolean mkdirs(String path);
    boolean delete(String path);
    boolean renameTo(String sourcePath, String targetPath);
    int replaceAtomically(String sourcePath, String targetPath);
    int scanDirectory(String path, in ParcelFileDescriptor sink);
    boolean startDeleteRecursively(
        String operationId,
        String path,
        in ResultReceiver receiver
    );
    oneway void cancelDeleteRecursively(String operationId);
}
