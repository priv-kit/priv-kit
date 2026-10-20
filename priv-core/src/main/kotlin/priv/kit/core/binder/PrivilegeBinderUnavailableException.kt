package priv.kit.core.binder

import android.os.DeadObjectException

/** No privileged server connection was available before forwarding the transaction. */
public class PrivilegeBinderUnavailableException : DeadObjectException("Privilege server is not connected")
