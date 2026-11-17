---
description: Priv Kit 的功能与模块选择。
---

# Priv Kit 是什么？ {#what-is-priv-kit}

Priv Kit 让 Android 应用通过 Root、ADB、手动命令或 Shizuku 启动自己的特权服务端。

## 功能 {#what-priv-kit-provides}

### Privilege UI {#privilege-ui}

用 [Privilege UI](./priv-ui) 接入 Compose 启动页，处理授权和配对。可在[展示页](/zh/playground/)体验。

### 用 priv-core 构建自定义界面 {#priv-core}

用 `priv-core` 在自定义界面中控制[启动和授权](./activation)。

### 通过 Binder 访问服务 {#binder}

通过 [Binder](./binder) 调用 Android 系统服务。服务接口由应用提供。

### 内置文件代理 {#file-proxy}

通过[文件代理](./file-proxy)读取、写入和遍历文件。

### 应用自定义 UserService {#user-service}

在特权 [UserService](./user-service) 中运行自己的 AIDL 实现。

## 每个应用独立运行 {#app-owned-runtime}

每个应用管理自己的服务端、连接和恢复。

## 从哪里开始 {#where-to-start}

先看[快速接入](./getting-started)，再按需使用[内置页面](./priv-ui)或[自定义界面](./activation)。
