---
description: 在嵌入式或独立特权进程中运行应用自定义 AIDL 服务。
---

# UserService {#user-service}

在特权进程里运行自己的 AIDL 服务或转发 Binder 调用。

## 默认嵌入特权服务端 {#default-process}

`PrivilegeUserServiceSpec` 默认使用 `EMBEDDED`，复用特权服务端进程及其 UID，减少启动、内存和进程管理开销。需要进程隔离时选择 `INHERIT`，也可以指定不同 UID。

业务状态尽量保存在应用主进程，详见[设计理念](./what-is-priv-kit#design-philosophy)。

## 无需自定义服务的 Binder 转发 {#binder-forwarding}

省略 `serviceClassName` 即可使用内置转发。目标 Android 服务接口由应用提供。

```kotlin
val connection = Privilege.bindUserService(
    PrivilegeUserServiceSpec(
        tag = "binder",
    ),
)
try {
    val binder = PrivilegeBinderWrapper.fromSystemService(
        serviceName = "launcherapps",
        userService = connection,
    )
    val launcherApps = ILauncherApps.Stub.asInterface(checkNotNull(binder))
} finally {
    connection.unbind()
}
```

默认在应用进程中查找系统服务，再由 UserService 转发调用。需要远端查找时，传入 `source = PrivilegeSystemServiceSource.REMOTE_PROCESS`。自定义 UserService 也支持这种转发。

## 指定进程 UID {#process-uid}

| `PrivilegeUserServiceProcess` | 运行方式 |
| --- | --- |
| `EMBEDDED`（默认） | 共用特权服务端进程及其 UID |
| `INHERIT` | 独立进程，继承服务端 UID |
| `ROOT` | 独立的 root 进程（UID `0`） |
| `SYSTEM` | 独立的 system 进程（UID `1000`） |
| `SHELL` | 独立的 shell 进程（UID `2000`） |

切换 UID 通常需要 root 权限，失败时 `bindUserService()` 会抛出异常。进程保留服务端的 SELinux 域。

切换到 shell 时，启动器会尝试复制 adbd 的补充用户组。如果 adbd 未运行或不可读取，仍会以空补充组列表继续启动；依赖这些用户组的操作可能失败。

## 解决 root 调用 getShortcuts 的包身份错误 {#launcherapps-shortcuts}

AOSP Android 17 的 [`LauncherAppsService`](https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/services/core/java/com/android/server/pm/LauncherAppsService.java#623) 在 `getShortcuts()` 中调用 `ensureShortcutPermission()`，再由 `verifyCallingPackage()` 校验包名。关键源码如下：

```java
if (!mPackageManagerInternal.isSameApp(callingPackage,
        PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_UNINSTALLED_PACKAGES,
        callerUid, UserHandle.getUserId(callerUid))) {
    throw new SecurityException("Calling package name mismatch");
}
```

[`isSameApp()`](https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/services/core/java/com/android/server/pm/PackageManagerService.java#7618) 先查询包 UID：

```java
Computer snapshot = snapshot();
int uid = snapshot.getPackageUid(packageName, flags, userId,
        Process.isPrivateComputeCoreUid(callingUid));
return UserHandle.isSameApp(uid, callingUid);
```

再由 [`UserHandle.isSameApp()`](https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/core/java/android/os/UserHandle.java#221) 比较两者的 appId：

```java
public static boolean isSameApp(int uid1, int uid2) {
    return getAppId(uid1) == getAppId(uid2);
}
```

root（UID `0`）是特殊的系统身份，不存在 `uid=0` 的 `callingPackage`，因此无法通过这里的包身份校验。

解决方式是同时使用 shell 进程和 shell 包名：

```kotlin
val connection = Privilege.bindUserService(
    PrivilegeUserServiceSpec(
        tag = "shortcuts",
        process = PrivilegeUserServiceProcess.SHELL, // 调用 UID：2000
    ),
)
try {
    val binder = PrivilegeBinderWrapper.fromSystemService(
        serviceName = "launcherapps",
        userService = connection,
    )
    val launcherApps = ILauncherApps.Stub.asInterface(checkNotNull(binder))
    val shortcuts = launcherApps.getShortcuts(
        "com.android.shell",
        query,
        targetUser,
    ).list
} finally {
    connection.unbind()
}
```

目标应用的包名在 `query` 中指定，调用包名保持为 `com.android.shell`。

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
import priv.kit.core.userservice.PrivilegeUserServiceEnvironment
import kotlin.system.exitProcess

class MyPrivilegeService @Keep constructor() : IMyPrivilegeService.Stub() {
    override fun getUid(): String {
        return "uid=${android.os.Process.myUid()}"
    }

    override fun destroy() {
        // 释放当前服务持有的资源。
        if (!PrivilegeUserServiceEnvironment.isEmbedded) {
            exitProcess(0)
        }
    }
}
```

服务需要 `Context` 时，可以改用接收 `Context` 的构造函数。

嵌入式服务只能释放自身资源，不能退出共享进程。通过 `stopUserService()` 销毁时，运行时会退出独立子进程，示例中的显式退出可省略。

## 使用独立进程 {#dedicated-process}

设置 `process = PrivilegeUserServiceProcess.INHERIT`，启动或复用继承服务端 UID 的独立进程：

```kotlin
lifecycleScope.launch {
    val spec = PrivilegeUserServiceSpec(
        serviceClassName = MyPrivilegeService::class.java.name,
        process = PrivilegeUserServiceProcess.INHERIT,
    )

    val connection = Privilege.bindUserService(spec)
    try {
        val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
        service.getUid()
    } finally {
        connection.unbind()
    }
}
```

`serviceClassName` 和 `tag` 共同标识实例。`tag` 默认为 `null`，需要同一个服务类的多个实例时设置不同 tag。需要重建时先停止再绑定。

## 生命周期与常驻 {#service-lifecycle}

`daemon` 控制整个实例是否常驻，默认 `false`：

| 操作 | 行为 |
| --- | --- |
| `bindUserService(spec, daemon = false)` | 创建或复用实例并建立连接，设置为非常驻；最后一个连接解绑或主进程死亡时销毁 |
| `bindUserService(spec, daemon = true)` | 创建或复用实例并建立连接，设置为常驻；解绑或主进程死亡后仍保留 |
| `peekUserService(spec)` | 只连接已有且 `process` 匹配的实例，不修改常驻策略 |
| `connection.unbind()` | 释放这次连接；非常驻实例在最后一个连接释放后销毁 |
| `stopUserService(spec)` | 停止类名和 tag 标识的整个实例，使全部连接失效 |

特权服务端退出时，所有 UserService 都会结束。主进程死亡会清理其连接，常驻实例可在重启后通过 `peekUserService()` 重连。

```kotlin
// 常驻服务在解绑后继续运行。
val connection = Privilege.bindUserService(spec, daemon = true)
connection.unbind()

// 停止整个服务，同时断开其余连接。
Privilege.stopUserService(spec)
```

相同实例标识和 `process` 下，再次 bind 会更新 `daemon`，不重建实例。省略参数也会取消常驻；只想保留策略并重连时用 `peekUserService()`。每次获取的连接都需显式解绑，丢弃对象不会解绑。

`stopUserService()` 返回时调用入口已关闭，但不等待断开通知、进程退出或已有调用结束。停止不存在的实例、重复解绑均无副作用。

## 观察连接状态 {#connection-state}

`connection.isConnected` 是 `StateFlow<Boolean>`，在解绑、服务停止/替换或进程死亡后变为 `false`，表示当前连接失效。

```kotlin
import kotlinx.coroutines.flow.first

val connection = Privilege.bindUserService(spec)
lifecycleScope.launch {
    connection.isConnected.first { connected -> !connected }
    // 更新界面，或释放与该连接相关的本地资源。
}
```

断开通知异步送达，已断开的连接不会恢复，需通过 bind 或 peek 获取新连接。失效连接上的业务调用和转发使用相同的[异常处理方式](./binder#failure-semantics)。

## 只连接已有实例 {#peek-existing-service}

`peekUserService()` 按 `serviceClassName + tag` 查找实例，要求 `process` 枚举值一致。不存在或不匹配时返回 `null`，不创建、替换实例或修改 `daemon`。

```kotlin
val connection = Privilege.peekUserService(spec)
if (connection != null) {
    try {
        val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
        service.getUid()
    } finally {
        connection.unbind()
    }
}
```

服务端未连接时抛出 `PrivilegeUnavailableException`；`null` 仅表示未找到匹配实例。

## 使用嵌入式服务 {#embedded-service}

省略 `process` 即使用默认的嵌入式模式：

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
)
```

## 判断运行环境 {#execution-environment}

`PrivilegeUserServiceEnvironment.isEmbedded` 为 `true` 表示嵌入式，为 `false` 表示独立进程，用法见 [`destroy()` 示例](#implement-service)。
