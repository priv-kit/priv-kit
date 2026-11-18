# Priv Kit 维护架构

接入方法见[网站指南](../priv-website/zh/guide/getting-started.md)，仓库规范见 [AGENTS.md](../AGENTS.md)。

## 范围与 API

每个应用和 Android 用户拥有各自的特权服务端。应用提供业务 AIDL 和所需的系统服务接口。公开包位于 `priv.kit.*`，Maven group 为 `io.github.priv-kit`。`priv-input` 和 `priv-evdev` 处于实验阶段。

## 模块

| 模块 | 职责 |
| --- | --- |
| `priv-core` | 启动、连接、Binder、文件、命令和 UserService |
| `priv-ui` | Compose 授权界面与恢复逻辑，Android 端依赖 Core |
| `priv-external-shizuku` | 实现 Core 外部启动接口，通过 Shizuku 运行 starter |
| `priv-shared`、`priv-adb-crypto` | 隐藏 API 兼容、ADB 加密及配对 |
| `priv-input`、`priv-evdev` | 系统输入注入、Linux 输入设备访问 |
| `priv-sample`、`priv-playground` | Android 接入示例、JVM/WasmJS 界面预览 |
| `hidden-api` | 编译及 R8 分析用的 framework 声明 |

Remap 随 AAR 生成隐藏类型和回调所需的 consumer rules。`:hidden-api` 声明不进入 APK。回调、重映射和 R8 的修改与验证见 [AGENTS.md](../AGENTS.md#android-hidden-apis-and-r8)。

## 启动与连接

所有入口执行同一 native starter，校验 root、system 或 shell UID，再经 Binder 交付连接。重复启动必须确认同一个 app/user 的旧进程已经退出，才启动替代进程。前台、静默启动和 owner 重连共用进程内仲裁器，以 `launchCorrelationId` 区分启动结果。跨进程协调由宿主负责。

控制、生命周期、文件、命令和 UserService Binder 作为同一连接发布。用生命周期 Binder 区分服务端实例，换连接后重新获取资源。Android 8–9 需要解压 native starter。

## Owner 重连

`followDeathDelayMillis` 控制服务端等待 owner 重连的时间，默认十分钟。`activeReconnectOnOwnerDeath` 可启用主动重连。六十秒内三次 owner 死亡会降级为被动等待，稳定存活六十秒后恢复。

`prepareOwnerRestart()` 只匹配随后五秒内的 owner 死亡。命中时先被动等待指定时间，这次退出不计入重启循环。

## Binder 与权限

服务端不可用使用 `PrivilegeServerUnavailableException`。转发时的 `DeadObjectException` 也可能来自目标 Binder。回复丢失后，调用方根据操作是否可安全重复来决定是否重试。权限从特权进程视角查询，实际访问仍受系统服务、AppOps 和 SELinux 限制。

`fromSystemService()` 和 `hasSystemService()` 默认在调用进程中查找服务，即使传入 `userService`。显式指定 `REMOTE_PROCESS` 时，由指定的 UserService 或主特权服务端查找。Binder 事务由 `userService` 指定的进程或主特权服务端发起。

## 文件与命令

服务端执行文件操作，客户端计算路径。流式内容通过管道传输，关闭输出流会等写入完成。目录遍历和递归删除都把符号链接视为叶子。递归删除拒绝根目录，部分失败不会回滚已删除内容。

命令直接执行 argv。`stream()` 和 `awaitResult()` 二选一，并行读取 stdout/stderr。超时、取消、owner 死亡或服务端关闭会终止未结束的命令。

## UserService

实例由 `serviceClassName + tag` 标识。没有类名时使用内置 Binder 转发。独立进程默认继承 UID，只有 root 服务端能切换 UID，SELinux 域保持继承。嵌入式服务只清理自身资源。

应用通过 connection 访问服务 Binder 和转发 Binder。解绑关闭该连接的转发能力，取消回收未被调用方接收的资源。运行时反射调用的构造函数需要保留。

## 输入

`priv-input` 和 `priv-evdev` 独立使用。前者通过系统 input Binder 注入 Android 事件，后者在特权进程中持有设备 fd，可经 UserService 导出。命令兼容和事件生成随 Android 版本变化，调用示例见[输入指南](../priv-website/zh/guide/input.md)。

Evdev 控制器只接受一个远程 owner。取消或关闭时先结束在途手势并尝试释放触点。原始事件写入由调用方管理接触状态，部分写入或结果未知时不重放整批。

## UI 与恢复

`commonMain` 提供布局、状态与资源，`androidMain` 对接 Core 和 Android 生命周期。外部启动使用 Core 的 `PrivilegeExternalStartProvider`，卡片从三态授权结果派生，具体失败原因进入启动日志。

被动检查只在宿主处于 resumed 且选中对应 Tab 时运行。静默恢复使用上次成功的 UI 启动方式和已有授权。断连或启动失败保留恢复设置，用户确认停止或关闭恢复时清除。前台和静默启动互斥。

## 文档与构建工具

`priv-website` 是中英文 VitePress 站点，`priv-playground` 提供 JVM/WasmJS 预览。仓库配置与验证范围见 [AGENTS.md](../AGENTS.md)。

## 验证

```shell
./gradlew :priv-sample:assembleLegacyRelease
```

在 `priv-website` 目录验证文档：

```shell
pnpm check
pnpm build
```
