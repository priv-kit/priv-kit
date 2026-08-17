package priv.kit.core.testing

import android.os.IBinder
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.core.PrivilegeServerHandshakeResult
import priv.kit.core.internal.core.PrivilegeServerServiceEndpoints

internal fun testHandshakeResult(
    serverInfo: PrivilegeServerInfo,
    serverBinder: IBinder,
    fileSystemBinder: IBinder = TestBinder(),
    userServiceManagerBinder: IBinder = TestBinder(),
): PrivilegeServerHandshakeResult =
    PrivilegeServerHandshakeResult(
        serverInfo = serverInfo,
        serverBinder = serverBinder,
        serviceEndpoints = PrivilegeServerServiceEndpoints(
            fileSystemBinder = fileSystemBinder,
            userServiceManagerBinder = userServiceManagerBinder,
        ),
    )
