---
description: 通过应用自有的 Priv Kit 服务端使用显式 Binder 原语。
---

# Binder

Priv Kit 连接应用与显式 Binder 端点或系统服务，并保留原始 transaction 契约。
应用提供 framework interface，并定义建立在这些原语之上的领域行为。

## 访问系统服务

通过已连接的 Privileged Server 解析显式服务名：

```kotlin
val activityBinder = PrivilegeBinderWrapper.fromSystemService("activity")
val activityManager = IActivityManager.Stub.asInterface(activityBinder)

Log.d(
    "activity",
    activityManager.getTasks(1).toString(),
)
```

部分服务必须在 shell 或 Root 服务端进程中解析：

```kotlin
val binder = PrivilegeBinderWrapper.fromSystemService(
    serviceName = "miui.mqsas.IMQSNative",
    source = PrivilegeSystemServiceSource.SERVER_PROCESS,
)
```

接入应用拥有 framework 接口，并负责定义每个 transaction 的含义。

## 理解失败语义

项目自有控制调用会把服务端缺失或死亡统一成
`PrivilegeServerUnavailableException`。Raw wrapper 调用会保留转发后的
Binder 失败，让应用按目标 Binder 或 Privileged Server 状态不确定的语义处理
恢复。

当应用为服务端或 UserService Binder 调用准备了显式 fallback 时，可以使用
`PrivilegeBinderCall.orElse(...)`：

- `PrivilegeBinderCallFailure.ServerUnavailable` 表示 Privileged Server
  不可用。
- `PrivilegeBinderCallFailure.BinderDied` 表示直接调用的端点死亡。
- 其他异常保留原始语义。

只为能够接受远端结果不确定性的恢复路径配置 fallback。远端进程可能在死亡前
已经完成修改操作。

## 把领域行为保留在应用中

库负责传输 Binder 调用，应用继续负责：

- 选择并编译需要的 hidden framework 接口；
- 校验参数与权限；
- 解释返回结果；
- 决定操作能否安全重试；
- 在应用自己的契约中定义高级 Android 操作。
