# Priv Kit 维护架构

接入方法见[网站指南](../priv-website/zh/guide/getting-started.md)，仓库规范见 [AGENTS.md](../AGENTS.md)。

## 范围与 API

每个应用和 Android 用户拥有各自的特权服务端。应用提供业务 AIDL 和所需的系统服务接口。公开包位于 `priv.kit.*`，Maven group 为 `io.github.priv-kit`。`priv-input` 和 `priv-evdev` 处于实验阶段。

## 设计理念

业务逻辑和状态尽量保存在应用主进程，特权侧尽量只做无状态转发，减少状态同步和重启恢复成本。连接、文件描述符和设备句柄按生命周期释放。

优先使用内置 Binder、文件和命令 API。自定义 UserService 默认嵌入已有特权服务端，减少额外进程、启动和内存开销；需要隔离或指定不同 UID 时再使用独立进程。

## 模块

| 模块 | 职责 |
| --- | --- |
| `priv-core` | 启动、连接、Binder、文件、命令和 UserService |
| `priv-ui` | 主题无关的状态、操作、Compose 宿主接入与恢复逻辑，Android 端依赖 Core |
| `priv-ui-m3` | Material 3 页面、面板、弹窗和模拟预览，依赖 `priv-ui` |
| `priv-external-shizuku` | 实现 Core 外部启动接口，通过 Shizuku 运行 starter |
| `priv-shared`、`priv-adb-crypto` | 隐藏 API 兼容、ADB 加密及配对 |
| `priv-input`、`priv-evdev` | 系统输入注入、Linux 输入设备访问 |
| `priv-sample`、`priv-playground` | Android 接入示例、JVM/WasmJS 界面预览 |
| `hidden-api` | 编译及 R8 分析用的 framework 声明 |

Remap 随 AAR 生成隐藏类型和回调所需的 consumer rules。`:hidden-api` 声明不进入 APK。回调、重映射和 R8 的修改与验证见 [AGENTS.md](../AGENTS.md#android-hidden-apis-and-r8)。

## 启动与连接

所有入口执行同一 native starter，校验 root、system 或 shell UID，再经 Binder 交付连接。重复启动会尽力清理同一个 app/user 的旧进程，跳过无法识别的 PID，不等待旧进程退出；清理仅在 `kill` 返回 `EPERM` 时中止启动。starter 在 fork 成功后返回，服务就绪由 Binder 连接确认。前台、静默启动和 owner 重连共用进程内仲裁器，以 `launchCorrelationId` 区分启动结果。跨进程协调由宿主负责。

控制、生命周期、文件、命令和 UserService Binder 作为同一连接发布。用生命周期 Binder 区分服务端实例，换连接后重新获取资源。Android 8–9 需要解压 native starter。

## Owner 重连

`followDeathDelayMillis` 控制服务端等待 owner 重连的时间，默认十分钟。`activeReconnectOnOwnerDeath` 可启用主动重连。六十秒内三次 owner 死亡会降级为被动等待，稳定存活六十秒后恢复。

`prepareOwnerRestart()` 只匹配随后五秒内的 owner 死亡。命中时先被动等待指定时间，这次退出不计入重启循环。

## Binder 与权限

特权通道断开或解绑使用 `PrivilegeUnavailableException`；目标或依赖 Binder 死亡及无法归类的 Binder 故障保留 `DeadObjectException`，不使健康连接断开。`PrivilegeBinderCall.orElse` 分别映射为 `Unavailable` 和 `BinderDied`，其他异常继续抛出。回复丢失后是否重试由调用方决定。权限从特权进程视角查询，仍受系统服务、AppOps 和 SELinux 限制。

业务调用和转发各使用一层回复封装，以状态码传递通道故障和目标死亡，避免 `Parcel.writeException` 不支持的异常类型。目标 Parcel 中的业务异常、Binder 和文件描述符原样保留。

`fromSystemService()` 和 `hasSystemService()` 默认在调用进程中查找服务，即使传入 `userService`。显式指定 `REMOTE_PROCESS` 时，由指定的 UserService 或主特权服务端查找。Binder 事务由 `userService` 指定的进程或主特权服务端发起。

## 文件与命令

服务端执行文件操作，客户端计算路径。流式内容通过管道传输，关闭输出流会等写入完成。目录遍历和递归删除都把符号链接视为叶子。递归删除拒绝根目录，部分失败不会回滚已删除内容。

命令直接执行 argv。`stream()` 和 `awaitResult()` 二选一，并行读取 stdout/stderr。超时、取消、owner 死亡或服务端关闭会终止未结束的命令。

## UserService

实例由 `serviceClassName + tag` 标识，tag 默认为 `null`，省略类名时使用内置 Binder 转发。默认 `EMBEDDED` 共用特权服务端；`INHERIT` 启动继承 UID 的独立进程。指定 UID 限于 root、system、shell，以使用特权 Provider 握手。身份切换由系统调用判定权限，SELinux 域保持继承；切换时清空补充组，shell 则尽力采用 adbd 的补充组。

`bindUserService(spec, daemon = false)` 创建或复用实例；标识和 process 相同时只更新 daemon 策略。非 daemon 实例在最后一个连接释放或 owner 死亡时销毁，daemon 实例继续保留。`peekUserService()` 仅连接标识和 process 匹配的已有实例，保留策略，否则返回 `null`。`stopUserService()` 停止整个实例；服务端退出时所有实例结束。

daemon 变更在客户端确认绑定结果后按请求序号提交，旧确认不覆盖新策略，取消会回收未接收的连接。客户端死亡清理其连接，重连创建新登记。

每个连接通过 ResultReceiver 接收断开通知，状态在发起请求前建立。`isConnected` 在解绑、实例停止/替换/死亡或服务端死亡后永久变为 false。

每个连接共用一个 gate 处理业务 AIDL 和转发；业务事务编号封装在请求内，避免与转发协议冲突。gate 位于实际执行进程，独立模式的调用直达子进程，主服务端只交付 Binder 引用并管理生命周期。解绑关闭单个 gate；停止同步关闭全部入口并移除登记，再通知断开和执行 destroy，不等待通知送达、进程退出或已有调用结束。

销毁通过约定的 Binder 事务调用服务的 `destroy()` 清理资源，运行时负责退出独立子进程，嵌入式服务保留共享进程。反射调用的构造函数需要保留。

## 输入

`priv-input` 和 `priv-evdev` 独立使用。前者通过系统 input Binder 注入 Android 事件，后者在特权进程中持有设备 fd，可经 UserService 导出。命令兼容和事件生成随 Android 版本变化，调用示例见[输入指南](../priv-website/zh/guide/input.md)。

Evdev 控制器只接受一个远程 owner。取消或关闭时先结束在途手势并尝试释放触点。原始事件写入由调用方管理接触状态，部分写入或结果未知时不重放整批。

## UI 与恢复

`priv-ui` 提供主题无关的业务状态、操作和 Android 平台接入，负责启动、配对、授权请求、轮询和恢复。`priv-ui-m3` 提供默认 Material 3 页面、展示状态与页面文本。外部启动通过 Core 的 `PrivilegeExternalStartProvider` 接入。

页面使用一个 VM：`PrivilegeM3ViewModel` 继承 `PrivilegeUiViewModel`。业务层描述配对输入与确认请求，渲染层决定用弹窗或内联组件呈现。M3 VM 管理 Tab、展开状态、提示和说明，直接观察业务授权状态；Compose 保留派生状态与组件局部状态。跨平台预览复用 M3 展示控制器。

`PrivilegeUiHost` 与 `rememberPrivilegeUi` 负责状态收集、本地化、生命周期和平台绑定，不绘制组件。授权流程由 VM 执行，业务协调器统一串行化并管理交互许可与取消；宿主可覆盖受保护的 `accessHandler`。Activity 只执行平台命令和交付结果，配置重建后重新绑定，真正离页或 VM 清理时取消授权流程。授权结果为 Boolean，取消通过 CancellationException 传播。

ADB 与外部 Provider 检查在页面 resumed 时按配置、权限及启动互斥条件运行，与 Tab 选择或组件可见性无关。`startupModes` 控制默认方式、自动启动顺序和观察来源，不限制显式启动操作。外部 Provider 自动补入 EXTERNAL，显式配置可指定顺序；有效方式列表必须非空。

静默恢复使用上次成功的 UI 启动方式和已有授权，与前台启动互斥。断连或启动失败保留恢复设置，用户确认停止或关闭恢复时清除。

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
