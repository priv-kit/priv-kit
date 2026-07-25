---
description: 通过应用自有的 Priv Kit 服务端访问 Binder 服务。
---

# Binder {#binder}

Priv Kit 可以连接 Binder 服务和系统服务。通过它执行 Binder transaction 时，
原有的调用格式不会改变。应用提供对应的系统接口，并负责实现自己的业务逻辑。

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

应用需要提供对应的系统接口，并定义每个 transaction 的调用格式和含义。

## 处理调用失败 {#failure-semantics}

Priv Kit 自己的控制调用在服务端未连接或已经死亡时，都会抛出
`PrivilegeServerUnavailableException`。通过 `PrivilegeBinderWrapper` 转发的
调用会保留 Binder 原本的异常。调用失败时，应用可能无法确定是目标 Binder
还是 Privileged Server 已经死亡，需要根据自身情况决定是否改用其他方式。

如果服务端或 UserService 的 Binder 调用失败后可以安全地改用其他方式，可以使用
`PrivilegeBinderCall.orElse(...)`：

- `PrivilegeBinderCallFailure.ServerUnavailable` 表示 Privileged Server
  不可用。
- `PrivilegeBinderCallFailure.BinderDied` 表示直接调用的 Binder 已经死亡。
- 其他异常保持不变。

只有在改用其他调用仍然安全时，才应使用 `orElse(...)`，因为应用可能无法确定
原调用是否已经完成。服务进程可能已经完成修改后才死亡。
