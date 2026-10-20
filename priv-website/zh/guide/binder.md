---
description: 通过应用自有的 Priv Kit 服务端访问 Binder 服务。
---

# Binder {#binder}

Priv Kit 通过特权服务端转发 Binder 调用，Android 服务接口由应用提供。

## 访问系统服务 {#system-service}

通过已连接的 Privileged Server，按服务名获取系统服务：

```kotlin
val activityBinder = PrivilegeBinderWrapper.fromSystemService("activity")
val activityManager = IActivityManager.Stub.asInterface(activityBinder)

Log.d(
    "activity",
    activityManager.getTasks(1).toString(),
)
```

部分服务只能在 shell 或 Root 服务端进程中获取：

```kotlin
val binder = PrivilegeBinderWrapper.fromSystemService(
    serviceName = "miui.mqsas.IMQSNative",
    source = PrivilegeSystemServiceSource.SERVER_PROCESS,
)
```

## 将资源绑定到服务端生命周期 {#server-lifecycle}

部分 Binder API 接收 owner 或 death token，以便远端进程在 owner 退出时释放资源。
如果资源应当跟随当前 Privileged Server 进程，可以使用专用的服务端生命周期 Binder：

```kotlin
val serverInfo = Privilege.getServerInfo()
val serverLifecycle = serverInfo.lifecycleBinder
serverLifecycle.linkToDeath(
    { Log.d("server", "Privileged Server 已退出") },
    0,
)
```

该 token 在服务端被替换前保持不变。`Privilege.serverState` 变化后重新获取；未连接时 `getServerInfo()` 抛出 `PrivilegeServerUnavailableException`。

## 处理调用失败 {#failure-semantics}

Core 控制调用在服务端不可用时抛出 `PrivilegeServerUnavailableException`。Binder 转发前没有连接时抛出 `PrivilegeBinderUnavailableException`（继承 `DeadObjectException`）；实际事务异常原样传递。

如果服务端或 UserService 的 Binder 调用失败后可以安全地改用其他方式，可以使用
`PrivilegeBinderCall.orElse(...)`：

- `PrivilegeBinderCallFailure.ServerUnavailable` 表示 Privileged Server
  不可用，包含上述两种服务不可用异常。
- `PrivilegeBinderCallFailure.BinderDied` 表示直接调用的 Binder 已经死亡。
- 其他异常保持不变。

`orElse(...)` 适合原调用结果未知时仍可安全恢复的场景。服务进程可能已经完成修改后
才死亡。
