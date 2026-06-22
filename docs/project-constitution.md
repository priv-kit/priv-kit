# 项目宪章

本文档是 `Priv Kit`（仓库名 `priv-kit`）的最高层设计契约。

后续所有设计、实现、评审、示例和公开 API 都必须遵守本宪章。如果其他文档与本宪章冲突，在本宪章被明确修改前，以本宪章为准。

## 1. 项目身份

项目命名固定如下：

- GitHub Organization：`priv-kit`
- GitHub Repository：`priv-kit`
- 项目对外名称：`Priv Kit`

`Priv Kit` 是一个面向单应用的自管理 Privileged Runtime。

本项目的存在目的，是让一个应用可以启动、连接并使用自己的 Privileged Server 进程。它是特权执行的基础设施，不是特权 Android 操作库。

## 2. 命名规则

命名规则是本项目的基础契约。后续创建工程骨架、模块、源码、示例和公开 API 时，都必须先满足本节规则。

### 2.1 Maven 命名

Maven `groupId` 固定为：

- `io.github.priv-kit`

Maven `artifactId` 固定使用：

- `priv-core`
- `priv-runtime`
- `priv-server`
- `priv-binder`
- `priv-user-service`
- `priv-bc`
- `priv-ssl`
- `priv-adb`
- `priv-root`
- `priv-delegate`
- `priv-ui`

示例：

```kotlin
implementation("io.github.priv-kit:priv-runtime:1.0.0")
```

### 2.2 Gradle 模块命名

Gradle 模块必须使用 `priv-*` 命名：

- `:priv-core`
- `:priv-runtime`
- `:priv-server`
- `:priv-binder`
- `:priv-user-service`
- `:priv-bc`
- `:priv-ssl`
- `:priv-adb`
- `:priv-root`
- `:priv-delegate`
- `:priv-ui`
- `:priv-sample`

内部 hidden framework stub 模块例外：

- `:hidden-api`

该模块只允许提供编译期 framework mirror/stub，不作为 Maven 产物发布，也不得承载运行时代码或项目公开 API。

### 2.3 Kotlin Package 命名

除 `:hidden-api` 中的 framework mirror/stub 外，所有源码 package 必须统一使用 `priv.kit.*`。

模块默认 package 根如下：

- `priv.kit.core`
- `priv.kit.runtime`
- `priv.kit.server`
- `priv.kit.binder`
- `priv.kit.userservice`
- `priv.kit.bc`
- `priv.kit.ssl`
- `priv.kit.adb`
- `priv.kit.root`
- `priv.kit.delegate`
- `priv.kit.ui`

禁止使用以下 package 命名：

- `io.github.xxx.*`
- `io.github.priv.*`
- `io.github.priv.kit.*`
- `privkit.*`

### 2.4 Public API 命名

所有公开 API 必须使用完整单词 `Privilege*` 命名。

允许示例：

- `PrivilegeKit`
- `PrivilegeLaunchMode`
- `PrivilegeServer`
- `PrivilegeBinder`
- `PrivilegeUserService`
- `PrivilegeRuntime`
- `PrivilegeConnection`

禁止示例：

- `PrivKit`
- `PrivSession`
- `PrivMode`
- `PrivServer`
- `PrivBinder`
- `PrivUserService`

`Privileged Server` 可以作为架构概念和说明性术语使用，但公开 API 的类型、接口、函数、属性和常量命名必须遵守完整单词规则。

## 3. 固定项目范围

本项目只能提供以下能力：

- 运行时生命周期
- Privileged Server 启动
- Privileged Server 连接
- Binder 能力
- UserService 能力
- Root 启动
- ADB 启动
- Delegate 启动
- 可选 Compose UI 帮助能力，用于观察和控制运行时生命周期
- 只演示项目自有能力范围的示例

除非先修改本宪章，否则这些范围之外的功能都属于非范围。

## 4. 明确非范围

本项目不得提供：

- Android 系统 API 兼容层
- AndroidX 风格的特权系统 API 封装
- ActivityManager 封装
- PackageManager 封装
- InputManager 封装
- Settings 封装
- AppOps 封装
- 高级 shell 命令库
- 高级设备自动化 API
- 高级包安装 API
- 高级权限、app-ops、输入、设置或进程管理抽象

本项目不得暴露以下形态的 API：

- `PrivilegeRuntime.input.tap(...)`
- `PrivilegeRuntime.package.install(...)`
- `PrivilegeRuntime.settings.put(...)`
- `PrivilegeRuntime.appops.setMode(...)`

这些能力属于应用或基于 `priv-kit` 构建的下游库。

## 5. 原语 API 规则

公开 API 必须保持原语化，并且围绕运行时展开。

允许的 API 类型：

- 选择和配置启动策略；
- 启动、停止和观察 Privileged Server 生命周期；
- 连接和重连 Privileged Server；
- 获取、注册或交换 Binder 端点；
- 以显式系统服务名创建 raw Binder transaction 桥；
- 启动、绑定、停止和观察 UserService 实例；
- 报告运行时状态、启动错误、连接错误和服务端身份；
- 可选 UI 状态帮助能力，用来呈现同一组运行时原语。

禁止的 API 类型：

- Android framework service 的类型化封装；
- 系统操作便利 API；
- 针对包、输入、设置、app-ops 或 activity 管理的策略决策；
- 用项目定义的领域 facade 隐藏应用自定义特权操作的 API。

如果某个 API 名称暗示了具体 Android 系统服务领域，默认应视为可疑。除非它严格用于内部运行时操作，或明确停留在显式服务名的 raw Binder transaction 桥，否则必须拒绝。

## 6. 单应用运行时规则

本项目面向一个应用管理自己的 Privileged Server。

本项目不得发展成：

- 设备级特权服务框架；
- 多租户特权服务注册中心；
- 通用插件平台；
- 面向无关应用的共享系统守护进程。

跨应用 Delegate 只能作为特定 Delegate 策略所需的启动机制存在。它不能扩展成通用特权服务市场或全局权限代理。

## 7. 启动策略规则

启动支持仅限于：

- Root 启动
- ADB 启动
- Delegate 启动

启动模块可以准备命令、启动服务端入口、传递必要参数并返回连接信息。它们不得把高级系统操作作为公开产品 API 暴露出去。

每种启动策略必须把自己的传输细节和失败细节留在本模块内部。运行时应通过共享抽象消费启动结果，不应依赖某个策略的实现细节。

## 8. Binder 规则

Binder 支持用于让客户端应用和 Privileged Server 交换底层 Binder 端点。

本项目可以提供：

- Binder 连接生命周期；
- Binder 端点注册和查找；
- Binder death 观察；
- Binder transaction 错误报告；
- 显式目标 Binder 的底层 remote transact 转发原语；
- 显式系统服务名的底层 remote transact 转发原语；
- 运行时操作需要的项目自有类型化 Binder 契约。

本项目不得为 activity、package、input、settings 或 app-ops 管理等 Android 系统服务提供类型化 Binder facade，也不得提供系统服务枚举或领域操作 API。

## 9. UserService 规则

UserService 支持用于让应用定义自己的特权服务逻辑，项目只管理生命周期和连接管线。

本项目可以提供：

- UserService 声明和身份原语；
- UserService start、bind、unbind 和 stop 生命周期；
- UserService 进程和连接状态报告；
- 客户端、运行时、服务端和 UserService 之间的 Binder handoff。

本项目不得在 UserService API 中定义应用业务能力。UserService 实现细节属于接入应用。

## 10. 模块边界规则

模块必须保持清晰归属：

- `:priv-core` 负责共享契约和值类型。
- `:priv-runtime` 负责客户端编排。
- `:priv-server` 负责 Privileged Server 进程行为。
- `:priv-binder` 负责 Binder 管线。
- `:priv-user-service` 负责 UserService 管线。
- `:priv-bc` 是 Kotlin/JVM-only 模块，负责项目内部使用的最小 BC 兼容 ASN.1 / X.509 证书生成能力，仅服务于 ADB 启动所需的客户端证书，不得依赖 Android API，也不得扩展成通用加密、证书管理或 PKI API。
- `:priv-ssl` 是 Kotlin/JVM-only 模块，负责项目内部使用的最小 BoringSSL 兼容 SPAKE2 / HKDF / AES-GCM 能力，仅服务于 ADB Wireless Debugging pairing，不得依赖 Android API，也不得扩展成通用 SSL、TLS 或密码学 API。
- `:priv-adb` 负责 ADB 启动。
- `:priv-root` 负责 Root 启动。
- `:priv-delegate` 负责 Delegate 启动。
- `:priv-ui` 负责可选 Compose UI 帮助能力。
- `:priv-sample` 只演示已支持的流程。
- `:hidden-api` 只提供编译期 hidden framework API stub，供示例或测试验证底层 Binder 能力。

任何模块都不得以“示例、UI 或便利性有用”为理由，引入高级 Android 系统能力封装。

项目自带 UI 统一使用 Jetpack Compose。`:priv-ui` 和 `:priv-sample` 禁止新增传统 Android View UI 逻辑，包括但不限于手写 `android.view.*` / `android.widget.*` 视图树、调用 `Activity#setContentView(...)` 装配界面、以及新增用于界面结构的 XML layout 文件。

## 11. 语言规则

除以下兼容场景外，所有手写源码都必须使用 Kotlin：

- hidden-api stub；
- framework mirror class；
- AIDL 兼容桥接。

普通业务模块不得新增 Java 源码。

所有 Gradle 构建脚本都必须使用 Kotlin DSL 和 `.gradle.kts` 扩展名。

## 12. 示例规则

示例只能演示：

- 启动 Privileged Server；
- 观察运行时状态；
- 连接 Privileged Server；
- 交换 Binder 端点；
- 使用应用自定义的 UserService 管线。

示例不得把包安装、输入注入、设置修改、app-ops 修改、activity 管理或其他高级系统操作展示成项目提供的能力。

`:priv-sample` 的应用界面必须使用 Jetpack Compose 实现，并遵守模块边界中的 Compose-only UI 规则。

如果示例需要一个特权操作来证明 Binder 或 UserService 可用，该操作必须明确归属于应用，并且保持最小化，不能被包装成可复用的项目 API。

示例可以包含最小 hidden API 调用来验证底层 Binder remote transact 是否可用，但该调用必须停留在 `:priv-sample`，不得上升为 `:priv-runtime`、`:priv-binder` 或其他发布模块中的类型化 Android 系统服务 API。发布模块只能暴露显式服务名的 raw Binder transaction 桥，不得把示例里的系统服务接口封装成项目 API。

## 13. 评审门禁

任何新的公开 API、模块、示例或文档被接受前，都必须通过以下问题：

1. 它是否服务于运行时、Binder、UserService 或启动？
2. 应用是否可以基于 Binder 或 UserService 自行实现它？
3. 它的名称是否暗示了高级 Android 系统服务封装，而不是显式服务名的 raw Binder transaction 桥？
4. 它是否在 Android framework API 之上创建了第二层抽象？
5. 它是否会鼓励出现 `PrivilegeRuntime.input.tap(...)` 或 `PrivilegeRuntime.package.install(...)` 这类 API？
6. 公开 API 是否使用 `Privilege*` 完整单词命名？
7. 源码 package 是否位于 `priv.kit.*`？

如果第 1 个问题的答案是否定，拒绝该变更。

如果第 2 到第 5 个问题中任何一个答案是肯定，默认拒绝该变更，除非本宪章被有意修改。

如果第 6 或第 7 个问题的答案是否定，拒绝该变更。

## 14. 宪章修改规则

修改本宪章是破坏性设计决策。

任何修订都必须：

- 说明正在改变哪条边界；
- 解释为什么该能力不能放在应用或下游库中；
- 列出受影响模块；
- 同步更新 README、架构、模块和示例；
- 如果公开 API 形态变化，提供迁移说明。

不得静默扩张范围。
