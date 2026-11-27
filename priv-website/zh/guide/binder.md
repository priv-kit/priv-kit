---
description: 通过应用自有的 Priv Kit 服务端访问 Binder 服务。
---

# Binder {#binder}

通过特权服务端调用 Android 系统服务。服务接口由应用提供。

## 访问系统服务 {#system-service}

`fromSystemService()` 和 `hasSystemService()` 默认在应用进程中按名称查找服务（`CURRENT_PROCESS`）。Binder 事务默认由特权服务端转发。使用应用中对应的 Android 接口声明。

```kotlin
val server = Privilege.getServerInfo()
val binder = checkNotNull(PrivilegeBinderWrapper.fromSystemService("activity"))
val activityManager = IActivityManager.Stub.asInterface(binder)
val granted = activityManager.checkPermission(
    android.Manifest.permission.WRITE_SECURE_SETTINGS,
    server.pid,
    server.uid,
) == android.content.pm.PackageManager.PERMISSION_GRANTED
```

## 何时使用 `REMOTE_PROCESS` {#remote-process}

应用进程按名称查不到服务、特权服务端能查到时，使用 `REMOTE_PROCESS`。传入 `userService = connection` 时，由对应的 UserService 进程查找。部分小米 ROM 上的 `miui.mqsas.IMQSNative` 就是一个例子。

```kotlin
val binder = PrivilegeBinderWrapper.fromSystemService(
    serviceName = "miui.mqsas.IMQSNative",
    source = PrivilegeSystemServiceSource.REMOTE_PROCESS,
)
```

要在设备上对比查找结果，连接示例应用的特权服务端，打开 **测试 Binder**，输入服务名并点击 **检查两个进程**。

### 读取 `meminfo` {#dump-meminfo}

按 AOSP Android 16 的默认策略，普通应用进程拿不到 `meminfo` 的 Binder。连接特权服务端后，向 `dump()` 传入 `-s` 和包名，读取该应用的内存摘要。

```kotlin
import android.os.ParcelFileDescriptor
import java.util.concurrent.FutureTask
import priv.kit.core.binder.PrivilegeBinderWrapper
import priv.kit.core.binder.PrivilegeSystemServiceSource

fun readMeminfo(packageName: String): String {
    val binder = checkNotNull(
        PrivilegeBinderWrapper.fromSystemService(
            serviceName = "meminfo",
            source = PrivilegeSystemServiceSource.REMOTE_PROCESS,
        ),
    )
    val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()

    return ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
        val output = FutureTask { input.bufferedReader().use { it.readText() } }
        writeEnd.use { writer ->
            Thread(output, "meminfo-reader").start()
            binder.dump(writer.fileDescriptor, arrayOf("-s", packageName))
        }
        output.get()
    }
}
```

## 通过 UserService 转发 {#user-service}

传入 `userService = connection`，通过已绑定的 [UserService](./user-service#binder-forwarding) 转发。解绑后代理失效。如果返回的 Binder 也需要转发，请单独包装。

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

普通转发和 UserService 调用使用相同的异常分类：

- `PrivilegeUnavailableException`：特权通道断开、服务停止或连接解绑，对应 `Unavailable`。
- `DeadObjectException`：目标或依赖 Binder 死亡，或无法归类的 Binder 故障，对应 `BinderDied`，不使健康的 UserService 连接断开。

可以直接捕获，或用 `PrivilegeBinderCall.orElse(...)` 回退。业务异常、普通 `RemoteException` 和协程取消仍会抛出。

```kotlin
val granted = PrivilegeBinderCall.orElse(
    fallback = { failure ->
        when (failure) {
            is PrivilegeBinderCallFailure.Unavailable -> Log.w("binder", "特权通道不可用", failure.exception)
            is PrivilegeBinderCallFailure.BinderDied -> Log.w("binder", "目标 Binder 失效", failure.exception)
        }
        false
    },
) {
    activityManager.checkPermission(permission, server.pid, server.uid) == PackageManager.PERMISSION_GRANTED
}
```

回复丢失前操作可能已经完成，只有可安全重试时才重复执行写操作。
