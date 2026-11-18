---
description: 在嵌入式或独立特权进程中运行应用自定义 AIDL 服务。
---

# UserService {#user-service}

在特权进程里运行自己的 AIDL 服务或转发 Binder 调用。

## 无需自定义服务的 Binder 转发 {#binder-forwarding}

省略 `serviceClassName` 即可使用内置转发。目标 Android 服务接口由应用提供。

```kotlin
val connection = Privilege.bindUserService(
    PrivilegeUserServiceSpec(
        tag = "shell-binder",
        process = PrivilegeUserServiceProcess(uid = 2000),
    ),
)
try {
    val binder = PrivilegeBinderWrapper.fromSystemService(
        serviceName = "launcherapps",
        userService = connection,
    )
    val launcherApps = ILauncherApps.Stub.asInterface(checkNotNull(binder))
    // 接口要求调用包名时，传入 connection.callingPackageName。
} finally {
    connection.unbind()
}
```

此示例在应用进程中查找 `launcherapps`，再通过 UserService 转发 Binder 调用。若要在 UserService 进程中查找服务，传入 `source = PrivilegeSystemServiceSource.REMOTE_PROCESS`。

`bindUserService()` 会按需启动服务并返回可用连接。用完后解绑。自定义 UserService 也支持这种转发。

## 指定进程 UID {#process-uid}

| `PrivilegeUserServiceProcess.uid` | 运行方式 |
| --- | --- |
| `null`（默认） | 独立进程，继承服务端 UID |
| `EMBEDDED`（`-1`） | 共用服务端进程 |
| `0`、`1000`、`2000` | 独立的 root、system 或 shell 进程 |

切换 UID 需要 root 服务端。进程保留服务端的 SELinux 域。用 `connection.uid` 查看实际 UID，用不同的 `tag` 区分实例。

## 解决 root 调用 getShortcuts 的包身份错误 {#launcherapps-shortcuts}

如果通过 root 调用 `LauncherApps.getShortcuts` 遇到 `Calling package name mismatch`，按上面的示例绑定 `uid = 2000` 的转发服务。通过该连接获取 `ILauncherApps`，调用包名传 `checkNotNull(connection.callingPackageName)`（`com.android.shell`）。目标应用的包名由查询参数指定。其他 Android 权限检查仍然适用。

## 定义 AIDL 接口 {#define-aidl-contract}

先在应用模块的 `build.gradle.kts` 中启用 AIDL。应用尚未引入 AndroidX 注解时，添加下方依赖以使用 `@Keep`。

```kotlin
android {
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.11.0")
}
```

将接口保存为 `src/main/aidl/com/example/app/IMyPrivilegeService.aidl`，把包名和目录换成应用实际使用的路径。`destroy()` 使用事务编号 `16777114`。

```java
package com.example.app;

interface IMyPrivilegeService {
    void destroy() = 16777114;
    String getUid() = 1;
}
```

## 实现服务 {#implement-service}

把 Kotlin 实现放在同一个包下，用 `@Keep` 保留运行时调用的构造函数。

```kotlin
package com.example.app

import androidx.annotation.Keep
import kotlin.system.exitProcess
import priv.kit.core.userservice.PrivilegeUserServiceEnvironment

class MyPrivilegeService @Keep constructor() : IMyPrivilegeService.Stub() {
    override fun getUid(): String {
        return "uid=${android.os.Process.myUid()}"
    }

    override fun destroy() {
        if (!PrivilegeUserServiceEnvironment.isEmbedded) {
            exitProcess(0)
        }
    }
}
```

服务需要 `Context` 时，可以改用接收 `Context` 的构造函数。

## 使用独立进程 {#dedicated-process}

绑定后会启动或复用独立进程并交付 Binder。需要结束实例时调用 `stopUserService()`。

```kotlin
lifecycleScope.launch {
    val spec = PrivilegeUserServiceSpec(
        serviceClassName = MyPrivilegeService::class.java.name,
        tag = "main",
        version = 1,
    )

    val connection = Privilege.bindUserService(spec)
    try {
        val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
        service.getUid()
    } finally {
        connection.unbind()
    }

    Privilege.stopUserService(spec)
}
```

`serviceClassName` 和 `tag` 标识实例。需要替换旧实例时更新 `version`。

## 使用嵌入式服务 {#embedded-service}

把 `process` 设为 `PrivilegeUserServiceProcess.EMBEDDED`，让服务运行在特权服务端进程中。

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    version = 1,
    process = PrivilegeUserServiceProcess.EMBEDDED,
)
```

## 判断运行环境 {#execution-environment}

在 `destroy()` 里检查 `PrivilegeUserServiceEnvironment.isEmbedded`。嵌入式服务只释放自身资源，只有独立服务可以调用 `exitProcess()`。
