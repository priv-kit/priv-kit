---
description: 在嵌入式或独立特权进程中运行应用自定义 AIDL 服务。
---

# UserService {#user-service}

UserService 在特权进程中运行自定义代码或内置 Binder 转发。

## 无需自定义服务的 Binder 转发 {#binder-forwarding}

省略 `serviceClassName` 即使用内置转发服务，不需要自定义业务类或 AIDL。应用仍需提供目标系统接口，例如 `ILauncherApps`。

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

自定义 UserService 的 connection 同样支持 `userService = connection`，业务 AIDL 无需新增任何方法。已有 Binder 可通过 `fromBinder(target, userService = connection)` 转发。系统服务查找和最终调用均在选中的进程执行。返回的 Binder 及回调不会递归包装；返回接口需要以该身份调用时，应显式再次包装。解绑后，该 connection 创建的 wrapper 失效，不会切换回主服务端。

`Privilege.bindUserService()` 成功返回时，进程启动、服务构造和 Binder 交付已完成，可以立即使用 connection；已有实例可能被复用。它不会等待自定义服务自行启动的异步初始化，也不保证主线程 Looper 已开始分发消息。

## 指定进程 UID {#process-uid}

| `PrivilegeUserServiceProcess.uid` | 运行方式 |
| --- | --- |
| `null`（默认） | 独立子进程，继承服务端身份 |
| `-1`，或 `EMBEDDED` | 嵌入服务端，共享现有身份 |
| `0` | 独立 root 进程 |
| `1000` | 独立 system UID 进程 |
| `2000` | 独立 shell UID 进程 |

切换 UID 要求 root 服务端；非 root 服务端只能继承身份或指定自己的 UID。其他 UID 值会被拒绝。root 切换至 UID 2000 时，原生启动器读取设备当前 adbd 的附加组，设置 GID/UID 为 2000，并清空 capabilities；无法读取 adbd 的组时启动失败。切换至 UID 1000 时设置 GID/UID 为 1000，清空附加组及 capabilities。进程保留父进程的 SELinux 域，不会复制完整的 adb shell 或 system_server 环境。

`connection.uid` 是实际进程 UID。`callingPackageName` 在 UID 1000 时为 `"android"`，UID 2000 时为 `"com.android.shell"`，其他情况为 `null`。需要验证调用包名的接口可显式传入该值；wrapper 不会修改 Parcel 中的包名，也不会绕过其他服务检查。

实例仍以类名和 tag 为键；改变 `process`、`version` 或 `daemon` 会替换该实例。需要同时保留多种身份时使用不同 tag。内置服务的类名为 null。

## 解决 root 调用 getShortcuts 的包身份错误 {#launcherapps-shortcuts}

如果通过 root 转发 `LauncherApps.getShortcuts` 时出现 `SecurityException("Calling package name mismatch")`，可配合使用 `process.uid` 与 `connection.callingPackageName`。Android 16 的 `LauncherAppsService.verifyCallingPackage` 直接比较包所属 UID 和 Binder 调用 UID，没有 root 豁免。root 的 UID 0 与应用自身、`com.android.shell`（2000）、`android`（1000）均不匹配。参见 [LauncherAppsService](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/pm/LauncherAppsService.java)。

`process = PrivilegeUserServiceProcess(uid = 2000)` 指定最终发起调用的进程身份；`userService = connection` 让 transaction 经过该进程；`connection.callingPackageName` 提供 `"com.android.shell"`，显式传给接口的调用包名参数。只改包名或只改 UID，仍会不匹配。UID 1000 配合 `"android"` 同样能满足这一包所属 UID 检查。

下面使用 Android 16 隐藏接口 `ILauncherApps.getShortcuts` 的签名。调用方准备 `query: ShortcutQueryWrapper` 和 `targetUser: UserHandle`，并自行提供对应版本的隐藏 API 访问声明。同步 Binder 调用应放在主线程之外。参见 [ILauncherApps](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/content/pm/ILauncherApps.aidl)。

```kotlin
val connection = Privilege.bindUserService(
    PrivilegeUserServiceSpec(
        tag = "shortcuts",
        process = PrivilegeUserServiceProcess(uid = 2000),
    ),
)
try {
    val launcherApps = ILauncherApps.Stub.asInterface(
        checkNotNull(
            PrivilegeBinderWrapper.fromSystemService(
                serviceName = "launcherapps",
                userService = connection,
            ),
        ),
    )
    val shortcuts = launcherApps.getShortcuts(
        checkNotNull(connection.callingPackageName),
        query,
        targetUser,
    )
    // 在这里使用 shortcuts。
} finally {
    connection.unbind()
}
```

`callingPackageName` 是调用方身份，不是待查询快捷方式所属的包；待查询的包在 `query` 中指定。已有的 `Context.getSystemService` 返回的 `LauncherApps` 不会自动改走转发，仍会携带其 Context 的包名。应使用上面经过包装的 `ILauncherApps`。

这解决的是 UID 与包名不匹配，不代表所有快捷方式调用失败都能解决。后续的快捷方式宿主权限检查接受 `ACCESS_SHORTCUTS`，AOSP Shell 声明了该权限；目标用户访问、用户解锁状态、查询 flags 和设备策略仍然生效。参见 [ShortcutService](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/pm/ShortcutService.java) 和 [Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/packages/Shell/AndroidManifest.xml)。root 降至 shell 仍要求 root 服务端及可读取的 adbd 附加组，具体见上文；指定 UID 2000 不会把 SELinux 域切换为 shell。

## 定义 AIDL 接口 {#define-aidl-contract}

将销毁方法的 transaction code 设为 `16777114`：

```java
interface IMyPrivilegeService {
    void destroy() = 16777114;
    String getUid() = 1;
}
```

## 实现服务 {#implement-service}

```kotlin
class MyPrivilegeService private constructor(
    private val context: Context?,
) : IMyPrivilegeService.Stub() {
    @Keep
    constructor() : this(context = null)

    @Keep
    constructor(context: Context) : this(context = context)

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

运行时支持无参构造器或 `Context` 构造器。独立进程优先初始化 Application，
失败后改用 package Context。嵌入式服务获得 package Context。

## 使用独立进程 {#dedicated-process}

这是默认模式。服务运行在单独的 `app_process` 子进程中：

```kotlin
lifecycleScope.launch {
    val spec = PrivilegeUserServiceSpec(
        serviceClassName = MyPrivilegeService::class.java.name,
        tag = "main",
        version = 1,
    )

    Privilege.startUserService(spec)

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

`startUserService`、`bindUserService` 和 `stopUserService` 是可取消的挂起函数。取消会移除待处理任务及尚未接收的资源。`connection.unbind()` 幂等，并在不可取消上下文完成清理。

每个实例由 `serviceClassName + tag` 标识。`version` 表达同一实例能否复用；实现不再
兼容时修改该值，运行时会替换实例。

## 使用嵌入式服务 {#embedded-service}

设置 `process = PrivilegeUserServiceProcess.EMBEDDED`，让服务直接运行在 Privileged Server 中，无需额外进程，节省进程启动和内存开销：

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    version = 1,
    process = PrivilegeUserServiceProcess.EMBEDDED,
)
```

嵌入式服务共享服务端进程，构造异步且可取消。

UserService 状态无效或操作返回失败时使用 `IllegalStateException`。
服务不可用、Binder 传输错误和协程取消保留各自的异常类型。
管理器或服务端点死亡时直接传播 `DeadObjectException`。内部响应协议跨进程保留
连接失效分类，因此 `PrivilegeBinderCall.orElse(...)` 无需检查嵌套 cause 即可处理。

## 判断运行环境 {#execution-environment}

`PrivilegeUserServiceEnvironment.isEmbedded` 在共享服务端进程中为 `true`，在独立子进程中为 `false`，进程存活期间不变。

退出进程前先检查该值：嵌入式服务只能清理自己的资源，不能调用 `exitProcess`，否则会结束整个特权进程。通过 `PrivilegeUserServiceSpec.process` 选择模式。
