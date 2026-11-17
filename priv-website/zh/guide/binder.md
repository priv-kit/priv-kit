---
description: 通过应用自有的 Priv Kit 服务端访问 Binder 服务。
---

# Binder {#binder}

通过特权服务端调用 Android 系统服务。服务接口由应用提供。

## 访问系统服务 {#system-service}

按名称查找服务，并使用应用中对应的 Android 接口声明。默认 `REMOTE_PROCESS` 在特权进程中查找，`CURRENT_PROCESS` 在应用进程中查找。同步服务调用放在后台线程。

```kotlin
val granted = withContext(Dispatchers.IO) {
    val server = Privilege.getServerInfo()
    val binder = checkNotNull(PrivilegeBinderWrapper.fromSystemService("activity"))
    val activityManager = IActivityManager.Stub.asInterface(binder)
    activityManager.checkPermission(
        android.Manifest.permission.WRITE_SECURE_SETTINGS,
        server.pid,
        server.uid,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
```

## 通过 UserService 转发 {#user-service}

传入 `userService = connection`，通过已绑定的 [UserService](./user-service#binder-forwarding) 转发。系统 API 要求调用包名时，使用 `connection.callingPackageName`。解绑后代理失效。如果返回的 Binder 也需要转发，请单独包装。

## 将资源绑定到服务端生命周期 {#server-lifecycle}

远程资源需要随服务端释放时，用 `Privilege.getServerInfo().lifecycleBinder`。`Privilege.serverState` 变化后重新获取。

```kotlin
val serverInfo = Privilege.getServerInfo()
val serverLifecycle = serverInfo.lifecycleBinder
serverLifecycle.linkToDeath(
    { Log.d("server", "Privileged Server 已退出") },
    0,
)
```

## 处理调用失败 {#failure-semantics}

`PrivilegeServerUnavailableException` 表示服务端不可用。`DeadObjectException` 也可能表示被转发的目标已经退出。需要安全回退时，用 `PrivilegeBinderCall.orElse(...)`。回复丢失前操作可能已经完成，只有可安全重试时才重复执行写操作。
