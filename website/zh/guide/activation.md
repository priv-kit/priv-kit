---
description: 比较 Priv Kit 的 Root、ADB、手动 shell 和外部激活路径。
---

# 选择激活方式

所有激活方式都会启动同一个应用自有 Privileged Server，并汇入同一条 Binder
移交路径。区别只在于最初的特权进程如何创建。

## 路径对比

| 路径 | 适用场景 | 用户交互 |
| --- | --- | --- |
| Root | 设备存在可用 Root 管理器 | Root 管理器可能请求授权 |
| 无线 ADB | 支持 Wireless Debugging 的 Android 设备 | 可能需要配对或授权 |
| 静态 TCP ADB | 已知 ADB TCP 端点 | 重启 ADB 前可能需要一次确认 |
| 手动 shell | 开发者或用户能够执行命令 | 应用展示可复制命令 |
| 外部桥 | Shizuku 或其他应用自有可信入口 | 由宿主集成定义 |

## Root

```kotlin
val serverInfo = Privilege.startRoot()
```

Root 启动会检查可用 `su` 路径，执行共享服务端命令，然后等待标准 Binder
移交。

## ADB

```kotlin
val serverInfo = Privilege.startAdb()
```

ADB 启动可以使用 Wireless Debugging 或显式配置的静态 TCP 端点。如果宿主
仍然声明并已经持有 `WRITE_SECURE_SETTINGS`，运行时可以临时打开 Wireless
Debugging 取得动态端口，并在启动完成后关闭。显式启动静态 TCP 前，如果持久化
端口仍然存在但 `adbd` 已停止监听，`PrivilegeAdbManager.prepareTcpForStart()`
可以只恢复核心 ADB 服务，不打开 Wireless Debugging。

Privileged Server 连接成功后，如果该权限仍被声明，且服务端是 Root 或拥有
`android.permission.GRANT_RUNTIME_PERMISSIONS`，运行时会尝试为所属应用补授
这个启动权限。托管能力不可用时，启动会继续使用手动配对和端口路径。公开接口
聚焦于启动流程，并为 `checkPermission(...)` 和
`grantRuntimePermission(...)` 提供显式的 PackageManager 参数透传。

通过 `adb tcpip` 创建或重启静态端点会影响其他依赖 ADB 的进程，因此内置 UI
会在执行前要求用户进行一次确认。取消后 ADB 保持原状态。复用或恢复已经
持久化的静态端口会直接进行；运行时能够恢复核心 ADB 服务时，即使 Wi-Fi
不可用也可以启动。

## 手动 shell

```kotlin
val nativeStarterPath = Privilege.nativeStarterPath
YourApp.showCommandToUser("adb shell $nativeStarterPath")
```

Core 返回已安装的 native starter SO 路径。宿主应用向开发机器展示命令时，
在路径前添加 `adb shell`。

内置 UI 会直接展示命令。实际生成的命令示例如下：

```shell
adb shell /data/app/~~-YKUdRFBwGAwYBVzJRt7pA==/priv.kit.sample.debug-A-2guZlsvRZ-9e6xF-K0kQ==/lib/arm64/libprivkitstarter.so
```

`/data/app/...` 路径会随设备、构建和安装结果变化。应用应展示
`Privilege.nativeStarterPath` 返回的路径，确保命令对应当前安装。该属性在每个
应用进程中只解析一次。

## 外部授权

运行时提供通用桥接机制，宿主应用继续负责第三方绑定与访问控制：

```kotlin
val nativeStarterPath = Privilege.nativeStarterPath
YourApp.bindUserServiceAndRun(nativeStarterPath)
```

对于应用自有桥，主进程可以调用
`PrivilegeExternalStartup.runThroughBridge(...)`，特权端委托给
`PrivilegeExternalStartupHost` 的单一启动方法。运行时负责
`ParcelFileDescriptor` 日志管道、实时输出、完成通知、超时，并确保同一时间
只有一个桥接调用。
`runInCurrentProcess(...)` 与 `createReceiver(...)` 继续作为底层 helper。
Shizuku 绑定、宿主 AIDL 契约和访问控制仍然属于应用。

## 失败与取消

Root、ADB、外部启动、配对、发现、TCP 操作和授权检查都是挂起 API。取消请求
会关闭活跃传输资源，并让同一个协程以取消结果恢复。如果独立服务端可能已经
启动，运行时会保持用户选择的激活方式，并把不确定结果报告给调用方。
