---
description: 了解 Priv Kit 提供什么、为何采用应用自有运行时，以及它的适用范围。
---

# Priv Kit 是什么？ {#what-is-priv-kit}

Priv Kit 是面向 Android 的应用自有特权运行时。它让单个应用可以通过 Root、ADB、
手动或外部授权器启动、连接并管理自己的 Privileged Server。连接成功后，
应用可以访问 Binder 服务或运行自己的 UserService，业务逻辑仍由应用自己实现。

大多数应用应先使用 `priv-ui`。它提供 Compose 授权页面，并处理启动、授权和状态
展示。
只有需要自定义授权界面时，才直接使用 `priv-core`。

想直接尝试？跳到[快速接入](./getting-started)。

## Priv Kit 提供什么 {#what-priv-kit-provides}

### 用 Privilege UI 完成常规接入 {#privilege-ui}

`priv-ui` 提供可直接嵌入的授权页面，包含启动状态、无线调试配对、权限请求、
TCP/IP 确认、手动和静默启动。应用可以直接使用这套流程，不必重新实现
这些交互。

<table>
  <tr>
    <td><img width="320" height="714" src="https://camo.githubusercontent.com/a22e4d6fa31e9e938bfe9a648f24dc0ddb2afa592fdad852dc4b8df953ad648a/687474703a2f2f652e676b642e6c692f32333461313665302d333463362d346364342d623631632d663632646332653761303838" /></td>
    <td><img width="320" height="714" src="https://camo.githubusercontent.com/8d6850f2c4fc5039e7dd7fab7f05985dbe0a02530494ce1aa1e235c31769a374/687474703a2f2f652e676b642e6c692f65616331303764662d393664362d346164372d383361342d656138303035636561363663" /></td>
  </tr>
  <tr>
    <td><img width="320" height="714" src="https://camo.githubusercontent.com/e43b81ac8bd9121b8d57e9afd219789677a8cd44e62a119387b7061a5889c4ad/687474703a2f2f652e676b642e6c692f36613439316261352d653330612d346436302d626164612d373234303931353932306330" /></td>
    <td><img width="320" height="714" src="https://camo.githubusercontent.com/8e6be96279e239f690394e4b28dca0d1f4263d4b0517514577388a1eb918a147/687474703a2f2f652e676b642e6c692f64663139663637662d646136352d346263662d623133632d623939386233613434623761" /></td>
  </tr>
</table>

### 用 priv-core 构建自定义界面 {#priv-core}

`priv-core` 提供 Root、无线 ADB、静态 TCP、手动和外部授权器所需的启动与连接
API。它不提供权限请求、配对码输入、确认界面或错误展示。需要自定义
授权界面的应用可以阅读[启动方式](./activation)，并自行实现这些交互。

### 通过 Binder 访问服务 {#binder}

Priv Kit 可以按服务名获取系统服务并执行底层 Binder transaction。应用负责提供
服务接口、定义每个 transaction 的调用格式，并处理调用失败。具体用法见
[Binder 指南](./binder)。

### 应用自定义 UserService {#user-service}

如果直接执行 Binder transaction 不方便，应用可以定义自己的 AIDL 接口和特权
实现。Priv Kit 负责启动 UserService、选择进程模式并把 Binder 返回给应用，接口
及其功能由应用自己实现。如何选择嵌入式或独立进程见
[UserService 指南](./user-service)。

## 为什么是应用自有运行时？ {#app-owned-runtime}

Priv Kit 只管理当前应用自己的服务，不在多个应用之间共享服务或权限。应用可以
自行选择启动方式、确认连接的是自己的服务端，并处理连接状态、断线恢复和特权
功能。

所有启动方式最终都使用同一套连接流程。连接失败或服务端死亡时，运行时会返回失败，
不会把无法确认的结果当作成功。

## 从哪里开始 {#where-to-start}

- 按照[快速接入](./getting-started)添加 `priv-ui` 并嵌入授权页面。
- 在 [Privilege UI](./priv-ui) 中配置自带的前台与静默流程。
- 只有使用 `priv-core` 构建自定义界面时，才阅读[启动方式](./activation)。
- 选择 [Binder](./binder) 执行底层 Binder transaction，或选择
  [UserService](./user-service) 运行应用自定义 AIDL 服务。
- 在 [GitHub](https://github.com/priv-kit/priv-kit) 浏览源码或反馈问题。
