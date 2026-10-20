---
description: Priv Kit 的功能与模块选择。
---

# Priv Kit 是什么？ {#what-is-priv-kit}

Priv Kit 让 Android 应用通过 Root、ADB、手动命令或外部授权器启动和管理自己的特权进程，用于 Binder 调用、文件操作、命令执行和自定义 UserService。

使用 `priv-ui` 接入 Compose 授权页面，或通过 `priv-core` 自建界面。

接入步骤见[快速接入](./getting-started)。

## 功能 {#what-priv-kit-provides}

### Privilege UI {#privilege-ui}

`priv-ui` 处理启动、无线调试配对、权限请求、TCP/IP 确认和静默启动。

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
API。自定义界面负责权限请求、配对码输入、确认和错误展示，底层流程见
[启动方式](./activation)。

### 通过 Binder 访问服务 {#binder}

按名称获取系统服务并转发 Binder 调用。应用提供服务接口并处理错误，见 [Binder](./binder)。

### 内置文件代理 {#file-proxy}

[文件代理](./file-proxy)在服务端执行文件操作，内容和目录项通过管道传输。

### 应用自定义 UserService {#user-service}

应用定义 AIDL 接口和特权实现，Priv Kit 负责启动 [UserService](./user-service)、管理生命周期并返回 Binder。

## 每个应用独立运行 {#app-owned-runtime}

每个应用独立管理自己的服务端，包括启动、连接和恢复。

各启动方式使用相同的连接检查，并报告断连和服务端死亡。

## 从哪里开始 {#where-to-start}

- 按照[快速接入](./getting-started)添加 `priv-ui` 并嵌入授权页面。
- 在 [Privilege UI](./priv-ui) 中配置自带的前台与静默流程。
- 使用 `priv-core` 构建自定义界面时，阅读[启动方式](./activation)。
- 选择[文件代理](./file-proxy)执行基础文件操作、选择 [Binder](./binder) 执行底层
  Binder transaction，或选择
  [UserService](./user-service) 运行应用自定义 AIDL 服务。
- 在 [GitHub](https://github.com/priv-kit/priv-kit) 浏览源码或反馈问题。
