# 架构设计

`Priv Kit`（仓库名 `priv-kit`）围绕一个小型特权运行时闭环组织：

1. 应用请求客户端运行时启动或连接；
2. 运行时选择启动策略；
3. 启动策略启动或触达 Privileged Server；
4. 运行时建立 Binder 连接；
5. 应用使用 Binder 或 UserService 原语运行自己的特权逻辑。

架构有意停在这些原语上。包管理、输入注入、设置修改、activity 控制、app-ops 修改等领域操作属于应用职责。

## 设计原则

- 公开接口保持原语化，不做领域封装。
- 将 Privileged Server 视为应用自有基础设施。
- 启动策略可替换，并隔离各自细节。
- Binder 和 UserService 是下游能力的扩展点。
- UI 模块只观察和控制生命周期。
- 优先提供明确状态和错误报告，不把恢复行为藏起来。
- 避免发展成兼容层。

## 命名约束

架构命名必须与 [project-constitution.md](project-constitution.md) 保持一致：

- GitHub Organization 和 Repository 都固定为 `priv-kit`。
- 项目对外名称固定为 `Priv Kit`。
- Maven `groupId` 固定为 `io.github.priv-kit`。
- 发布模块的 Maven `artifactId` 使用 `priv-*`：`priv-core`、`priv-runtime`、`priv-server`、`priv-binder`、`priv-user-service`、`priv-bc`、`priv-ssl`、`priv-adb`、`priv-root`、`priv-delegate`、`priv-ui`。
- Gradle 模块使用 `:priv-core`、`:priv-runtime`、`:priv-server`、`:priv-binder`、`:priv-user-service`、`:priv-bc`、`:priv-ssl`、`:priv-adb`、`:priv-root`、`:priv-delegate`、`:priv-ui`、`:priv-sample`，以及内部编译期 stub 模块 `:hidden-api`。
- 除 `:hidden-api` 中的 framework mirror/stub 外，Kotlin package 统一使用 `priv.kit.*`，例如 `priv.kit.runtime`、`priv.kit.server`、`priv.kit.binder`、`priv.kit.userservice`。
- 禁止使用 `io.github.xxx.*`、`io.github.priv.*`、`io.github.priv.kit.*` 或 `privkit.*` 作为源码 package。
- 公开 API 使用完整单词 `Privilege*`，例如 `PrivilegeKit`、`PrivilegeRuntime`、`PrivilegeConnection`。
- 公开 API 禁止使用 `Priv*` 缩写，例如 `PrivKit`、`PrivSession`、`PrivRuntime`、`PrivConnection`。

示例依赖坐标：

```kotlin
implementation("io.github.priv-kit:priv-runtime:1.0.0")
```

## 语言和构建约束

除以下情况外，所有手写源码统一使用 Kotlin：

- hidden-api stub；
- framework mirror class；
- AIDL 兼容桥接。

所有 Gradle 脚本统一使用 Kotlin DSL，即 `*.gradle.kts`。

禁止在普通模块新增 Java 源码。

## 高层组件

```text
Application
    |
    v
:priv-runtime
    |
    +-------------------+--------------------+----------------------+
    |                   |                    |                      |
    v                   v                    v                      v
:priv-root          :priv-adb          :priv-delegate          :priv-ui
    |                   |                    |
    +-------------------+--------------------+
                        |
                        v
                  :priv-server
                        |
              +---------+----------+
              |                    |
              v                    v
          :priv-binder       :priv-user-service
              |                    |
              +---------+----------+
                        |
                        v
                   :priv-core
```

`:priv-core` 是共享契约基础。其他模块通过它共享值类型、状态模型和错误分类。

## 运行时生命周期

运行时是客户端侧的协调者，负责以下状态机：

- idle；
- starting；
- connecting；
- connected；
- reconnecting；
- stopping；
- stopped；
- failed。

运行时必须提供足够信息，让应用决定下一步动作，但不得把这些状态转成领域化 Android 操作。

运行时预期职责：

- 接收启动配置；
- 选择启动策略；
- 启动或触达 Privileged Server；
- 建立 Binder 连接；
- 监听服务端死亡；
- 在配置允许时重连；
- 向可选 UI 模块暴露连接状态；
- 提供 Binder 和 UserService 原语入口。

## 服务端角色

Privileged Server 是以特权运行的运行时端点。

服务端预期职责：

- 使用启动策略传入的参数完成 bootstrap；
- 发布项目自有 Binder 端点；
- 在平台允许时验证连接客户端是否为目标应用；
- 管理 Binder 端点交换；
- 托管或协调 UserService 生命周期；
- 报告服务端身份、协议版本和生命周期状态。

服务端不得暴露项目定义的高级系统 API。它可以执行应用自定义的 UserService 逻辑，但这些逻辑不属于本项目的公开能力面。

## 启动策略

项目支持三类启动：

- `:priv-root` 中的 Root 启动
- `:priv-adb` 中的 ADB 启动
- `:priv-delegate` 中的 Delegate 启动

每种策略把应用配置转换成服务端启动或连接尝试。策略模块可以了解 shell 命令、启动参数、传输搭建和策略特有诊断。运行时通过共享契约消费它们的结果。

共享的 `app_process` 服务端启动命令由运行时根据核心启动值模型构造。Root、ADB 和 Delegate 模块只负责各自的命令执行通道和失败诊断。运行时负责 token、pending handshake、全局 server-binder 安装和 death handling。

启动策略、启动来源和服务端实际运行身份是三个概念：Root、ADB 或 Delegate 描述命令由哪种策略执行；`PrivilegeLaunchMode` 只记录服务端命令按 root 入口还是 shell 入口启动；服务端最终运行身份以 `PrivilegeServerInfo.uid` 和 `pid` 为准。因此即使某些设备让服务端以 uid=1000 等系统身份运行，运行时也不会把它强行归类为 root 或 shell 权限等级。

Delegate 启动由应用提供 `PrivilegeDelegateExecutor`。`priv-kit` 只把 runtime 构造好的共享启动命令交给 executor 并等待同一条 Binder handoff；Shizuku 等第三方能力可以作为应用侧 executor，但不进入 `:priv-delegate` 的模块实现。

启动策略不得变成操作库。Root 模块可以通过 root 启动服务端，但不得提供用于包安装、输入事件、设置写入、app-ops 修改或其他系统操作的公开 root helper。

## Binder 架构

Binder 是最低层的特权通信原语。

Binder 支持应覆盖：

- 连接 Privileged Server 的 Binder 端点；
- Binder 端点注册；
- Binder 端点查找；
- Binder death recipient 处理；
- transaction 失败传播；
- 显式目标 Binder 的 remote transact 转发；
- 显式系统服务名的 raw remote transact 转发；
- 项目自有契约的协议和版本检查。

当前 Binder 原语由 `:priv-binder` 承载：

- `IPrivilegeServer` 定义项目自有 Privileged Server Binder 协议；
- `PrivilegeBinderClient` 作为 `PrivilegeRuntime` 内部 helper 支撑单 endpoint 访问入口；
- `PrivilegeBinderRegistry` 作为服务端内存 endpoint slot，负责注册、查找、注销和 death 自动清理；
- `PrivilegeBinderEndpoint` 和 `PrivilegeBinderRegistration` 提供应用侧 Binder 句柄和注册生命周期；
- `PrivilegeRemoteBinderWrapper` 将显式目标 `IBinder` 的 `transact` 通过当前 Privileged Server 执行，并通过 `PrivilegeRuntime` 的全局 server-binder getter 在每次 transaction 前统一拦截 server 断连；
- `PrivilegeRemoteSystemServiceBinder` 将显式系统服务名的 `transact` 通过当前 Privileged Server 执行，由服务端在自己的 SELinux 域内解析该服务名；
- `PrivilegeBinderException` 是 Binder 原语异常密封基类，`PrivilegeServerDisconnectedException`、`PrivilegeBinderEndpointDeadException`、`PrivilegeBinderEndpointNotFoundException` 和 `PrivilegeBinderRemoteCallException` 提供可按类型捕获的失败语义。

Binder 支持不应覆盖：

- Android framework service 的类型化封装；
- 高级特权操作 facade；
- hidden framework API 的兼容层。
- 系统服务枚举、系统服务领域发现 API 或可复用的类型化系统 Binder facade。

应用可以基于本项目的 Binder 管线定义自己的 Binder 契约。

## UserService 架构

UserService 是应用自定义特权逻辑的扩展机制。

项目负责生命周期管线：

- 声明 UserService 身份；
- 请求 start 或 bind；
- 将客户端 Binder 连接到服务 Binder；
- 观察服务死亡；
- unbind 或 stop；
- 暴露生命周期错误。

当前实现由 `:priv-user-service` 承载：

- `PrivilegeUserServiceSpec` 使用 `serviceClassName + tag` 标识一个应用自定义 UserService 实例；
- `version` 不是实例身份的一部分，只控制同一 `serviceClassName + tag` 是否复用现有实例；
- 当 `serviceClassName + tag` 相同但 `version` 变化时，旧实例会被销毁并由新实例替换；
- 默认 `PrivilegeUserServiceProcessMode.DEDICATED_PROCESS` 为每个实例启动独立 `app_process` 子进程；
- `PrivilegeUserServiceProcessMode.IN_SERVER_PROCESS` 只作为显式 opt-in，用于低风险、短耗时、可接受污染 server 进程的服务；
- `PrivilegeUserServiceOwnerDeathPolicy.DESTROY_ON_OWNER_DEATH` 是默认 owner death 行为；
- `PrivilegeUserServiceSpec.destroyTimeoutMillis` 只作用于独立进程模式，默认 `10_000` 毫秒，`0` 表示不等待，负数表示关闭 destroy 超时强杀兜底；
- UserService 类本身必须实现 `IBinder` 或 `IInterface`，常见形式是直接继承应用自己的 AIDL `Stub`；
- UserService 类可以声明无参构造器、单个 `android.content.Context` 构造器，或两个都声明；如果检测到 `Context` 构造器，会优先使用该构造器；
- 应用自己的 AIDL Binder 由 UserService 暴露，项目只做 Binder handoff，不理解业务接口；
- 如果应用 AIDL 定义了 `void destroy() = 16777114;`，项目会在移除实例时调用该预留 transaction 供应用清理资源；一旦 AIDL 中有方法显式指定 id，该接口内所有方法都需要显式指定 id。

Release 构建中，应对所有可能被 Priv Kit 反射调用的 UserService 构造器标注 `androidx.annotation.Keep`。Kotlin 中可以用私有主构造器承接共享状态，再显式声明两个 secondary constructor：`@Keep constructor() : this(context = null)` 和 `@Keep constructor(context: Context) : this(context = context)`。调用方应使用 `MyService::class.java.name` 生成 `serviceClassName`，不要硬编码源码类名字符串。

独立进程模式下，Privileged Server 是控制平面，UserService 子进程是执行平面。server 通过 app 侧 handshake provider claim 子进程的控制 Binder，再向客户端返回 gate Binder。UserService 如果声明 `Context` 构造器，子进程会先创建 package `Context`，再优先尝试 `LoadedApk.makeApplication(true, null)` 得到应用 `Application`；如果该 framework 路径抛错，会记录日志并回退到 package `Context`。owner app 死亡或 server shutdown 时，server 会按策略向 UserService 发出 destroy 请求；复杂服务应在自己的 `destroy()` 中完成资源释放，并在释放完成后自行调用 `System.exit(0)`。如果 `destroyTimeoutMillis` 到期后子进程仍然存活，server 会强制 kill 该进程作为兜底；如果该值为负数，server 只发出 destroy 请求，不等待也不因为 destroy 超时强杀进程。

嵌入模式下，server 在自己的进程内用当前 APK classpath 反射创建 UserService 对象；如果服务声明 `Context` 构造器，只传入 package `Context`，不会调用 `makeApplication`，也不会创建或安装应用 `Application`。如果 package `Context` 创建失败且服务同时声明了无参构造器，会回退到无参构造器；只有 context-only 服务会继续报声明错误。该模式只面向轻量、短耗时、低风险逻辑；`destroy()` 可以不实现，如果实现也只应做快速资源释放，不能调用 `System.exit()`。销毁只能移除 registry 记录、关闭 gate Binder 并调用可选的 reserved destroy transaction，不能卸载 class、清理 static/native 状态或阻止服务代码杀死 server 进程。

服务行为由应用负责。本项目不应在 UserService API 中定义可复用特权功能。

## UI 架构

`:priv-ui` 是可选 Compose UI 层。

它可以提供：

- 运行时状态展示；
- 启动方式选择界面；
- 连接和错误展示帮助能力；
- start、stop、reconnect、bind、unbind 等生命周期动作控件。

它不得提供：

- 系统操作界面；
- 包管理工具；
- 输入工具；
- 设置编辑器；
- app-ops 编辑器；
- 特权自动化控制台。

UI 模块只映射运行时原语，不扩展项目范围。

项目 UI 策略统一使用 Jetpack Compose。`:priv-ui` 和 `:priv-sample` 都禁止使用传统 Android View UI 逻辑，例如手写 `android.view.*` / `android.widget.*` 视图树、通过 `Activity#setContentView(...)` 装配界面、或新增用于界面结构的 XML layout 文件。

## 错误模型

错误应按项目自有关注点分组：

- startup unavailable；
- startup denied；
- startup command failed；
- server bootstrap failed；
- connection timeout；
- protocol mismatch；
- Binder died；
- UserService failed to start；
- UserService disconnected；
- delegate unavailable；
- invalid configuration。

错误应携带可行动的诊断上下文，但不得把某个策略的内部细节泄漏到无关模块。

## 安全模型

安全姿态很简单：本项目帮助一个应用管理自己的特权运行时，应避免发展成通用特权代理。

设计要求：

- 在平台允许时优先做显式应用身份校验；
- 保持服务端入口收窄；
- 让启动参数可审计；
- 不暴露特权操作的全局注册表；
- 不把跨应用访问作为默认能力；
- 将 Delegate 启动视为启动策略，而不是广泛的第三方能力共享。

## 兼容模型

项目只能在运行时自身需要时包含兼容桥接代码。

允许的兼容区域：

- 服务端或 Binder bootstrap 所需的 hidden-api stub；
- 编译或绑定运行时契约所需的 framework mirror class；
- 项目自有 Binder 协议所需的 AIDL 兼容桥接。

禁止的兼容区域：

- 大范围 Android 系统服务封装；
- 面向下游应用功能的便利兼容 API；
- 类似 AndroidX 的 framework 抽象层。

## 扩展模型

下游应用通过以下方式扩展本项目：

- 定义自己的 Binder 契约；
- 实现自己的 UserService 逻辑；
- 选择启动策略；
- 自行决定自己的代码执行哪些特权操作。

本项目应让这些扩展点可靠，但不应把下游操作吸收到自己的 API 中。

## 验证预期

后续实现应通过以下方式验证：

- 运行时状态转换测试；
- 启动策略契约测试；
- Binder 连接和死亡处理测试；
- UserService 生命周期测试；
- 在可行时检查 sample app 的 Root、ADB 和 Delegate 流程；
- API 评审时检查项目宪章。

验证必须包含负向检查，确保禁止的高级 API 不会被引入。
